/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;
import dezz.status.widget.shell.PrivilegedShell;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    public static final int FOREGROUND_PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;
    public static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1003;
    public static final int BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE = 1004;

    private static final String EXPORT_FILE_NAME_PREFIX = "status-widget-settings-";
    private static final String EXPORT_FILE_NAME_EXT = ".json";
    private static final String EXPORT_MIME_TYPE = "application/json";

    private Preferences prefs;

    ActivityMainBinding binding;

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importSettings(uri);
                }
            });

    private final CompoundButton.OnCheckedChangeListener enableWidgetSwitchListener =
            (buttonView, isChecked) -> {
                if (isChecked) {
                    if (Permissions.allPermissionsGranted(this)) {
                        startWidgetService();
                    } else {
                        requestPermissions();
                    }
                } else {
                    stopWidgetService();
                    prefs.overlayX.reset();
                    prefs.overlayY.reset();
                }
            };

    private void uncheckEnableSwitchSilently() {
        binding.sectionGeneral.enableWidgetSwitch.setOnCheckedChangeListener(null);
        binding.sectionGeneral.enableWidgetSwitch.setChecked(false);
        binding.sectionGeneral.enableWidgetSwitch.setOnCheckedChangeListener(enableWidgetSwitchListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);

        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        applyWindowInsets();

        binding.sectionGeneral.aboutButton.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        binding.sectionGeneral.settingsButton.setOnClickListener(this::showSettingsMenu);

        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.versionText.setText(appVersion);
        } else {
            binding.versionText.setVisibility(View.GONE);
        }

        initializeViews();

        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startWidgetService();
        }

        maybeShowCrashReport();
        tryAutoGrantViaPrivilegedShell();
    }

    /**
     * Some car head units (notably Geely Monjaro / QUALCOMM KX11) ship a stripped-down
     * Settings package that doesn't expose Usage Access / Notification Listener UIs at all,
     * so the user has no way to grant those permissions manually. As a workaround we try to
     * speak ADB or Telnet to the head unit itself — those listeners are exposed by the
     * factory ROM on the loopback interface — and run {@code appops}/{@code cmd notification}
     * over that channel with shell-uid privileges. Silent no-op when permissions are already
     * granted or no privileged channel is reachable.
     */
    private void tryAutoGrantViaPrivilegedShell() {
        PrivilegedShell.Request.Builder rb = PrivilegedShell.Request.forPackage(getPackageName());
        boolean any = false;

        if (!Permissions.checkOverlayPermission(this)) {
            rb.withOverlay();
            any = true;
        }
        if (!Permissions.checkForMissingForegroundPermissions(this).isEmpty()) {
            rb.withForegroundLocation();
            any = true;
        }
        if (!Permissions.isBackgroundLocationGranted(this)) {
            rb.withBackgroundLocation();
            any = true;
        }
        if (!Permissions.isUsageAccessGranted(this)) {
            rb.withUsageAccess();
            any = true;
        }
        // Notification Listener is only useful when the media brick is in the current layout;
        // gating on it avoids granting (and toasting about) a permission the user doesn't need.
        boolean mediaPresent = BrickType.parseOrder(prefs.brickOrder.get()).contains(BrickType.MEDIA);
        if (mediaPresent && !Permissions.isNotificationAccessGranted(this)) {
            rb.withNotificationListener(PrivilegedShell.notificationListenerComponent(
                    getPackageName(), MediaNotificationListener.class));
            any = true;
        }
        if (!any) {
            return;
        }

        // Capture the application context, NOT this Activity. Discovery can take 10+ s and
        // the activity may already be destroyed (rotation, finish) by the time the callback
        // fires; using `this` for Toast/getString would either leak the Activity through the
        // pending lambda or post a Toast against a destroyed context.
        final Context appCtx = getApplicationContext();
        PrivilegedShell.get(this).ensurePrivileges(rb.build(), result -> {
            if (!result.transportAvailable) {
                // No reachable ADB/Telnet — silently fall back to the manual settings path
                // (which the user will hit when they tap "Enable widget" or "Hide in apps…").
                return;
            }
            if (result.anyGranted()) {
                Toast.makeText(appCtx,
                        appCtx.getString(R.string.privileged_grant_success,
                                joinPermissionLabels(appCtx, result.grantedKinds)),
                        Toast.LENGTH_LONG).show();
                // If the user already had the widget enabled before and we were only blocked
                // on permissions, autostart the service now — saves them having to toggle it.
                if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)
                        && !WidgetService.isRunning()) {
                    startWidgetService();
                }
            }
            if (result.anyFailed()) {
                Toast.makeText(appCtx,
                        appCtx.getString(R.string.privileged_grant_failed,
                                joinPermissionLabels(appCtx, result.failedKinds)),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Localised, comma-separated permission labels for a {@link PrivilegedShell.GrantResult}. */
    private static String joinPermissionLabels(Context ctx,
                                               java.util.List<PrivilegedShell.PermissionKind> kinds) {
        java.util.List<String> labels = new java.util.ArrayList<>(kinds.size());
        for (PrivilegedShell.PermissionKind k : kinds) {
            labels.add(ctx.getString(permissionLabelRes(k)));
        }
        return TextUtils.join(", ", labels);
    }

    private static int permissionLabelRes(PrivilegedShell.PermissionKind kind) {
        switch (kind) {
            case OVERLAY:             return R.string.permission_label_overlay;
            case FOREGROUND_LOCATION: return R.string.permission_label_foreground_location;
            case BACKGROUND_LOCATION: return R.string.permission_label_background_location;
            case USAGE_ACCESS:        return R.string.permission_label_usage_access;
            case NOTIFICATION:        return R.string.permission_label_notification;
            default: throw new IllegalArgumentException("Unknown kind " + kind);
        }
    }

    private void maybeShowCrashReport() {
        File crashFile = new File(getCacheDir(), StatusWidgetApplication.CRASH_FILE);
        if (!crashFile.exists() || !crashFile.canRead()) {
            return;
        }
        String content;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(crashFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            content = sb.toString();
        } catch (IOException e) {
            // Unreadable — best we can do is delete and bail.
            //noinspection ResultOfMethodCallIgnored
            crashFile.delete();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.crash_report_title)
                .setMessage(content)
                .setNeutralButton(R.string.crash_report_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "Status Widget crash", content));
                    }
                    Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show();
                    //noinspection ResultOfMethodCallIgnored
                    crashFile.delete();
                })
                .setPositiveButton(R.string.crash_report_share, (d, w) -> shareCrashReport(crashFile))
                .setNegativeButton(R.string.crash_report_dismiss, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    crashFile.delete();
                })
                .show();
    }

    private void shareCrashReport(File crashFile) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", crashFile);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Status Widget crash");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.crash_report_chooser)));
            // File stays on disk until the user comes back; on next maybeShowCrashReport() they can
            // dismiss/copy/share again.
        } catch (Throwable t) {
            Log.w(TAG, "Failed to share crash report", t);
        }
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    private void exportSettings() {
        try {
            String json = prefs.exportToJson();
            File exportsDir = new File(getCacheDir(), "exports");
            if (!exportsDir.exists() && !exportsDir.mkdirs()) {
                Toast.makeText(this, R.string.export_failed_toast, Toast.LENGTH_LONG).show();
                return;
            }
            String fileName = EXPORT_FILE_NAME_PREFIX
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(new java.util.Date())
                    + EXPORT_FILE_NAME_EXT;
            File file = new File(exportsDir, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(EXPORT_MIME_TYPE);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, fileName);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.export_chooser_title)));
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            Toast.makeText(this, R.string.export_failed_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings(Uri uri) {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open input stream");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            prefs.importFromJson(builder.toString());
        } catch (Preferences.InvalidSettingsFileException e) {
            Log.w(TAG, "Invalid settings file", e);
            Toast.makeText(this, R.string.import_invalid_file_toast, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
            Toast.makeText(this, R.string.import_failed_toast, Toast.LENGTH_LONG).show();
            return;
        }

        boolean wasRunning = WidgetService.isRunning();
        if (wasRunning) {
            stopService(new Intent(this, WidgetService.class));
        }
        Toast.makeText(this, R.string.import_success_toast, Toast.LENGTH_SHORT).show();
        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startForegroundService(new Intent(this, WidgetService.class));
        }
        recreate();
    }

    private BrickListAdapter brickAdapter;

    private void initializeViews() {
        binding.sectionGeneral.enableWidgetSwitch.setChecked(prefs.widgetEnabled.get());
        binding.sectionGeneral.enableWidgetSwitch.setOnCheckedChangeListener(enableWidgetSwitchListener);

        bindDropdown(
                binding.sectionAppearance.iconDesignDropdown,
                R.array.icon_designs,
                prefs.iconDesign);

        bindDropdown(
                binding.sectionAppearance.iconStyleDropdown,
                R.array.icon_styles,
                prefs.iconStyle);

        bindDropdown(
                binding.sectionAppearance.widgetThemeDropdown,
                R.array.widget_themes,
                prefs.widgetTheme);

        bindDropdown(
                binding.sectionGeneral.widgetModeDropdown,
                R.array.widget_modes,
                prefs.widgetMode,
                () -> {
                    refreshFloatingControlsEnabled();
                    if (brickAdapter != null) {
                        brickAdapter.notifyDataSetChanged();
                    }
                });

        ViewBinder binder = new ViewBinder(this);

        binder.bindCheckbox(binding.sectionGeneral.widgetAlignRightSwitch, prefs.widgetAlignRight);

        binder.bindColorComponentSlider(binding.sectionAppearance.backgroundAlphaSlider, prefs.backgroundAlpha);
        binder.bindPercentSlider(binding.sectionAppearance.backgroundCornerRadiusSlider, prefs.backgroundCornerRadius);

        binding.sectionGeneral.hideInAppsButton.setOnClickListener(v -> openAppSelection());

        setupPositionSliders(binder);
        setupBrickList();
    }

    private void setupPositionSliders(ViewBinder binder) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        binding.sectionGeneral.widgetPositionXSlider.setValueFrom(0F);
        binding.sectionGeneral.widgetPositionXSlider.setValueTo(Math.max(1, dm.widthPixels));
        binding.sectionGeneral.widgetPositionYSlider.setValueFrom(0F);
        binding.sectionGeneral.widgetPositionYSlider.setValueTo(Math.max(1, dm.heightPixels));
        binder.bindSizeSlider(binding.sectionGeneral.widgetPositionXSlider, prefs.overlayX);
        binder.bindSizeSlider(binding.sectionGeneral.widgetPositionYSlider, prefs.overlayY);
        binder.bindSizeSlider(binding.sectionGeneral.widgetPaddingLeftSlider, prefs.paddingLeft);
        binder.bindSizeSlider(binding.sectionGeneral.widgetPaddingRightSlider, prefs.paddingRight);
        binder.bindSizeSlider(binding.sectionGeneral.widgetPaddingTopSlider, prefs.paddingTop);
        binder.bindSizeSlider(binding.sectionGeneral.widgetPaddingBottomSlider, prefs.paddingBottom);
        refreshFloatingControlsEnabled();
    }

    /** Position sliders, right-edge anchor switch and corner radius only matter in floating mode. */
    private void refreshFloatingControlsEnabled() {
        boolean floating = prefs.widgetMode.get() != 1;
        binding.sectionGeneral.widgetAlignRightSwitch.setEnabled(floating);
        binding.sectionGeneral.widgetPositionXSlider.setEnabled(floating);
        binding.sectionGeneral.widgetPositionYSlider.setEnabled(floating);
        binding.sectionAppearance.backgroundCornerRadiusSlider.setEnabled(floating);
    }

    private void setupBrickList() {
        brickAdapter = new BrickListAdapter(this, prefs, this::refreshAddBrickChips);
        binding.sectionLayout.brickList.setLayoutManager(new LinearLayoutManager(this));
        binding.sectionLayout.brickList.setAdapter(brickAdapter);

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder source,
                                  @NonNull RecyclerView.ViewHolder target) {
                brickAdapter.moveBrick(source.getBindingAdapterPosition(),
                        target.getBindingAdapterPosition());
                return true;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // No swipe-to-dismiss; user removes via the in-card button.
            }
        });
        itemTouchHelper.attachToRecyclerView(binding.sectionLayout.brickList);
        brickAdapter.attachItemTouchHelper(itemTouchHelper);

        refreshAddBrickChips();
    }

    private void refreshAddBrickChips() {
        ChipGroup chipGroup = binding.sectionLayout.addBrickChips;
        chipGroup.removeAllViews();
        List<BrickType> current = brickAdapter.getBricks();
        boolean anyMissing = false;
        for (BrickType type : BrickType.values()) {
            if (current.contains(type)) continue;
            anyMissing = true;
            Chip chip = new Chip(this);
            chip.setText(brickTitle(type));
            chip.setCheckable(false);
            chip.setChipIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_input_add));
            chip.setOnClickListener(v -> brickAdapter.addBrick(type));
            chipGroup.addView(chip);
        }
        binding.sectionLayout.addBrickLabel.setVisibility(anyMissing ? View.VISIBLE : View.GONE);
        chipGroup.setVisibility(anyMissing ? View.VISIBLE : View.GONE);
    }

    private String brickTitle(BrickType type) {
        switch (type) {
            case TIME:
                return getString(R.string.brick_title_time);
            case DATE:
                return getString(R.string.brick_title_date);
            case MEDIA:
                return getString(R.string.brick_title_media);
            case WIFI:
                return getString(R.string.brick_title_wifi);
            case GPS:
                return getString(R.string.brick_title_gps);
            case BLUETOOTH:
                return getString(R.string.brick_title_bluetooth);
            default:
                return "";
        }
    }

    private void bindDropdown(MaterialAutoCompleteTextView dropdown, int arrayRes, Preferences.Int preference) {
        bindDropdown(dropdown, arrayRes, preference, null);
    }

    private void bindDropdown(MaterialAutoCompleteTextView dropdown, int arrayRes,
                              Preferences.Int preference, @Nullable Runnable onChange) {
        String[] items = getResources().getStringArray(arrayRes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, com.google.android.material.R.layout.m3_auto_complete_simple_item, items);
        dropdown.setAdapter(adapter);
        int current = Math.max(0, Math.min(preference.get(), items.length - 1));
        dropdown.setText(items[current], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            preference.set(position);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
            if (onChange != null) {
                onChange.run();
            }
        });
    }

    private final android.os.Handler overlayRegisterHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onResume() {
        super.onResume();
        registerOverlayListener();
    }

    @Override
    protected void onPause() {
        super.onPause();
        overlayRegisterHandler.removeCallbacksAndMessages(null);
        if (WidgetService.isRunning()) {
            WidgetService.getInstance().setOverlayStateListener(null);
        }
    }

    private void registerOverlayListener() {
        if (binding == null) return;
        if (!WidgetService.isRunning()) {
            // Service starts asynchronously after onCreate's startForegroundService; poll
            // until it's up so the position sliders + status-bar padding pick up its state.
            overlayRegisterHandler.postDelayed(this::registerOverlayListener, 200);
            return;
        }
        WidgetService.getInstance().applyPreferences();
        WidgetService.getInstance().setOverlayStateListener((x, y, w, h) ->
                runOnUiThread(() -> updatePositionSliders(x, y, w, h)));
    }

    private void updatePositionSliders(int x, int y, int w, int h) {
        if (binding == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Slider sx = binding.sectionGeneral.widgetPositionXSlider;
        Slider sy = binding.sectionGeneral.widgetPositionYSlider;
        int xMin = -(w / 2);
        int xMax = dm.widthPixels;
        int yMin = -(h / 2);
        int yMax = dm.heightPixels;
        applySliderRange(sx, xMin, xMax, x);
        applySliderRange(sy, yMin, yMax, y);
        updateStatusBarPadding(h);
    }

    /**
     * In status-bar widget mode the floating widget sits at y=0 full-width and would cover the
     * top of the settings cards. Push the scroll content down by the widget's height (on top of
     * the normal section spacing).
     */
    private void updateStatusBarPadding(int widgetHeight) {
        if (binding == null) return;
        View child = binding.scrollView.getChildAt(0);
        if (child == null) return;
        int basePaddingTop = getResources().getDimensionPixelSize(R.dimen.sectionSpacing);
        int extra = (prefs.widgetMode.get() == 1) ? widgetHeight : 0;
        child.setPadding(
                child.getPaddingLeft(),
                basePaddingTop + extra,
                child.getPaddingRight(),
                child.getPaddingBottom());
    }

    private static void applySliderRange(Slider slider, int min, int max, int value) {
        int clamped = Math.max(min, Math.min(max, value));
        // Material Slider validates the (from, to, value) triple on every setter, so widen the
        // bounds first to ensure the current value stays inside the range during transitions.
        slider.setValueFrom(Math.min(slider.getValueFrom(), Math.min(min, clamped)));
        slider.setValueTo(Math.max(slider.getValueTo(), Math.max(max, clamped)));
        slider.setValue(clamped);
        slider.setValueFrom(min);
        slider.setValueTo(max);
    }

    private void openAppSelection() {
        if (!Permissions.isUsageAccessGranted(this)) {
            Toast.makeText(this, R.string.usage_access_required, Toast.LENGTH_LONG).show();
            openUsageAccessSettings();
            return;
        }
        try {
            startActivity(new Intent(this, AppSelectionActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch AppSelectionActivity", t);
            String message = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Toast.makeText(this,
                    getString(R.string.app_selection_load_failed_message, message),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openUsageAccessSettings() {
        if (!SettingsLauncher.openUsageAccessSettings(this)) {
            Toast.makeText(this, R.string.system_settings_not_available, Toast.LENGTH_LONG).show();
        }
    }

    private void startWidgetService() {
        prefs.widgetEnabled.set(true);
        startForegroundService(new Intent(this, WidgetService.class));
    }

    private void stopWidgetService() {
        prefs.widgetEnabled.set(false);
        stopService(new Intent(this, WidgetService.class));
    }

    private void showSettingsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.settings_actions, menu.getMenu());
        // Show icons next to titles. PopupMenu hides them by default; this reaches into the
        // private menu helper to force-show, which Material's PopupMenu supports.
        try {
            java.lang.reflect.Field f = menu.getClass().getDeclaredField("mPopup");
            f.setAccessible(true);
            Object popup = f.get(menu);
            popup.getClass()
                    .getDeclaredMethod("setForceShowIcon", boolean.class)
                    .invoke(popup, true);
        } catch (Exception ignored) {
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_presets) {
                startActivity(new Intent(this, PresetsActivity.class));
                return true;
            }
            if (id == R.id.menu_export) {
                exportSettings();
                return true;
            }
            if (id == R.id.menu_import) {
                importLauncher.launch(new String[]{EXPORT_MIME_TYPE, "*/*"});
                return true;
            }
            if (id == R.id.menu_reset) {
                confirmResetSettings();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmResetSettings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_settings_title)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_settings_confirm, (d, w) -> resetAllSettings())
                .show();
    }

    private void resetAllSettings() {
        if (WidgetService.isRunning()) {
            stopService(new Intent(this, WidgetService.class));
        }
        prefs.resetAll();
        // Start a fresh MainActivity instead of recreate(): recreate() restores the View
        // hierarchy state after our initializeViews(), and the CompoundButton listeners we
        // attached then fire on setChecked(...) from restoreInstanceState — writing the
        // pre-reset values straight back into the prefs we just cleared.
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
        finish();
    }

    private void requestPermissions() {
        List<String> foregroundMissing = Permissions.checkForMissingForegroundPermissions(this);

        if (!foregroundMissing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    foregroundMissing.toArray(new String[0]),
                    FOREGROUND_PERMISSION_REQUEST_CODE);
        } else if (!Permissions.isBackgroundLocationGranted(this)) {
            requestBackgroundLocationPermission();
        } else if (!Permissions.checkOverlayPermission(this)) {
            requestOverlayPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == FOREGROUND_PERMISSION_REQUEST_CODE) {
            if (!Permissions.checkForMissingForegroundPermissions(this).isEmpty()) {
                uncheckEnableSwitchSilently();
                Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
            } else if (!Permissions.isBackgroundLocationGranted(this)) {
                requestBackgroundLocationPermission();
            } else if (!Permissions.checkOverlayPermission(this)) {
                requestOverlayPermission();
            } else {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                startWidgetService();
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            if (!Permissions.isBackgroundLocationGranted(this)) {
                uncheckEnableSwitchSilently();
                Toast.makeText(this, R.string.background_location_required, Toast.LENGTH_LONG).show();
            } else if (!Permissions.checkOverlayPermission(this)) {
                requestOverlayPermission();
            } else {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                startWidgetService();
            }
        }
    }

    private void requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, R.string.background_location_settings_hint, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to open application details settings", t);
                uncheckEnableSwitchSilently();
                Toast.makeText(this, R.string.system_settings_not_available, Toast.LENGTH_LONG).show();
            }
            return;
        }

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
    }

    public void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open overlay permission settings", t);
            uncheckEnableSwitchSilently();
            Toast.makeText(this, R.string.system_settings_not_available, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Permissions.checkOverlayPermission(this)) {
                startWidgetService();
            } else {
                uncheckEnableSwitchSilently();
                Toast.makeText(this, R.string.overlay_permission_required,
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE) {
            if (!Permissions.isBackgroundLocationGranted(this)) {
                uncheckEnableSwitchSilently();
                Toast.makeText(this, R.string.background_location_required, Toast.LENGTH_LONG).show();
            } else if (!Permissions.checkOverlayPermission(this)) {
                requestOverlayPermission();
            } else {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                startWidgetService();
            }
        }
    }
}
