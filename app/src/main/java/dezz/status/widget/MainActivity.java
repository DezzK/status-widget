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

    private final ActivityResultLauncher<String[]> presetImportLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importPresetFromUri(uri);
                }
            });

    @Nullable
    private UserPresets.UserPreset pendingExportPreset;
    private final ActivityResultLauncher<String> presetExportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
            uri -> {
                UserPresets.UserPreset src = pendingExportPreset;
                pendingExportPreset = null;
                if (uri != null && src != null) {
                    writePresetToUri(src, uri);
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
        Uri appUri = Uri.parse("package:" + getPackageName());
        Intent direct = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, appUri);
        if (direct.resolveActivity(getPackageManager()) != null) {
            startActivity(direct);
            return;
        }
        Intent generic = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        if (generic.resolveActivity(getPackageManager()) != null) {
            startActivity(generic);
            return;
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri));
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
            if (id == R.id.menu_apply_preset) {
                showPresetsDialog();
                return true;
            }
            if (id == R.id.menu_save_preset) {
                showSavePresetDialog();
                return true;
            }
            if (id == R.id.menu_import_preset) {
                presetImportLauncher.launch(new String[]{EXPORT_MIME_TYPE, "*/*"});
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

    /** Unified row in the preset picker — either a bundled {@link Presets.Preset} or a user one. */
    private static final class PresetEntry {
        final String name;
        @Nullable
        final String subtitle;
        @Nullable
        final Presets.Preset bundled;
        @Nullable
        final UserPresets.UserPreset user;

        private PresetEntry(String name, @Nullable String subtitle,
                            @Nullable Presets.Preset bundled,
                            @Nullable UserPresets.UserPreset user) {
            this.name = name;
            this.subtitle = subtitle;
            this.bundled = bundled;
            this.user = user;
        }

        static PresetEntry bundled(Context ctx, Presets.Preset p) {
            return new PresetEntry(ctx.getString(p.nameRes), ctx.getString(p.descRes), p, null);
        }

        static PresetEntry user(Context ctx, UserPresets.UserPreset u) {
            return new PresetEntry(u.name, ctx.getString(R.string.preset_user_subtitle), null, u);
        }

        boolean isUser() {
            return user != null;
        }
    }

    private void showPresetsDialog() {
        final List<PresetEntry> entries = new ArrayList<>();
        for (Presets.Preset p : Presets.ALL) {
            entries.add(PresetEntry.bundled(this, p));
        }
        for (UserPresets.UserPreset u : UserPresets.list(this)) {
            entries.add(PresetEntry.user(this, u));
        }
        ArrayAdapter<PresetEntry> adapter = new ArrayAdapter<PresetEntry>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                PresetEntry e = entries.get(position);
                ((android.widget.TextView) view.findViewById(android.R.id.text1)).setText(e.name);
                android.widget.TextView text2 = view.findViewById(android.R.id.text2);
                if (e.subtitle == null || e.subtitle.isEmpty()) {
                    text2.setVisibility(View.GONE);
                } else {
                    text2.setVisibility(View.VISIBLE);
                    text2.setText(e.subtitle);
                }
                return view;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_apply_preset)
                .setAdapter(adapter, (d, which) -> {
                    PresetEntry e = entries.get(which);
                    if (e.isUser()) {
                        showUserPresetActions(e.user);
                    } else if (e.bundled != null) {
                        confirmApplyBundledPreset(e.bundled);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmApplyBundledPreset(Presets.Preset preset) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_apply_title)
                .setMessage(getString(R.string.preset_apply_message, getString(preset.nameRes)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preset_apply_confirm,
                        (d, w) -> applyBundledPreset(preset))
                .show();
    }

    private void applyBundledPreset(Presets.Preset preset) {
        String json;
        try {
            json = Presets.readJson(this, preset);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read preset asset " + preset.assetFile, e);
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        applyPresetJson(json, getString(preset.nameRes));
    }

    /**
     * Shared tail of the apply-preset flow: imports the JSON, restarts the widget service if
     * the preset asks for it, shows a toast, and restarts the activity. Used both by bundled
     * presets and by user presets.
     */
    private void applyPresetJson(String json, String displayName) {
        try {
            prefs.importFromJson(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply preset " + displayName, e);
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (WidgetService.isRunning()) {
            stopService(new Intent(this, WidgetService.class));
        }
        // Presets generally set widgetEnabled=true. Auto-start the service if permissions allow so
        // the new look takes effect immediately instead of waiting for a manual toggle.
        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startForegroundService(new Intent(this, WidgetService.class));
        }
        Toast.makeText(this, getString(R.string.preset_applied_toast, displayName),
                Toast.LENGTH_SHORT).show();
        // Same restart pattern as resetAllSettings: a brand-new MainActivity avoids the View
        // state-restoration footgun where attached listeners write the old values back.
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
        finish();
    }

    private void showUserPresetActions(UserPresets.UserPreset preset) {
        String[] items = {
                getString(R.string.preset_action_apply),
                getString(R.string.preset_action_export),
                getString(R.string.preset_action_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(preset.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: applyUserPreset(preset); break;
                        case 1: exportUserPreset(preset); break;
                        case 2: confirmDeleteUserPreset(preset); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyUserPreset(UserPresets.UserPreset preset) {
        String json;
        try {
            json = UserPresets.read(preset.file);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read user preset " + preset.file, e);
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        applyPresetJson(json, preset.name);
    }

    private void showSavePresetDialog() {
        showPresetNameDialog(
                R.string.preset_save_title,
                R.string.preset_save_confirm,
                "",
                name -> {
                    String json;
                    try {
                        json = prefs.exportToJson(name);
                    } catch (org.json.JSONException e) {
                        Log.e(TAG, "Failed to serialize current prefs", e);
                        Toast.makeText(this, R.string.preset_save_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    savePresetWithName(name, json);
                });
    }

    private void importPresetFromUri(Uri uri) {
        String json;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open input stream");
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            json = builder.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read preset", e);
            Toast.makeText(this, R.string.preset_import_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // Validate the file by sniffing for the fileType field — don't actually apply yet.
        if (!json.contains("\"dezz.status.widget.settings\"")) {
            Toast.makeText(this, R.string.import_invalid_file_toast, Toast.LENGTH_LONG).show();
            return;
        }
        String suggested = Preferences.readPresetName(json);
        if (suggested == null) {
            suggested = "";
        }
        final String jsonFinal = json;
        showPresetNameDialog(
                R.string.preset_import_title,
                R.string.preset_save_confirm,
                suggested,
                name -> {
                    // Re-stamp the JSON with the chosen display name so subsequent exports keep it.
                    String rewritten = jsonFinal;
                    try {
                        org.json.JSONObject root = new org.json.JSONObject(jsonFinal);
                        root.put("presetName", name);
                        rewritten = root.toString(2);
                    } catch (org.json.JSONException ignored) {
                    }
                    savePresetWithName(name, rewritten);
                });
    }

    /**
     * Common write path for save-current and import: validate the sanitised filename slug,
     * confirm overwrite if a preset with the same slug already exists, then write the file.
     */
    private void savePresetWithName(String displayName, String json) {
        String slug = UserPresets.sanitiseFilename(displayName);
        if (slug.isEmpty()) {
            Toast.makeText(this, R.string.preset_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        File existing = new File(new File(getFilesDir(), "user_presets"), slug + ".json");
        if (existing.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.preset_overwrite_title)
                    .setMessage(getString(R.string.preset_overwrite_message, displayName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preset_overwrite_confirm,
                            (d, w) -> writeUserPresetFile(displayName, json))
                    .show();
        } else {
            writeUserPresetFile(displayName, json);
        }
    }

    private void writeUserPresetFile(String displayName, String json) {
        try {
            UserPresets.save(this, displayName, json);
            Toast.makeText(this, getString(R.string.preset_saved_toast, displayName),
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write user preset " + displayName, e);
            Toast.makeText(this, R.string.preset_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void exportUserPreset(UserPresets.UserPreset preset) {
        pendingExportPreset = preset;
        presetExportLauncher.launch(UserPresets.exportFilename(preset.name));
    }

    private void writePresetToUri(UserPresets.UserPreset preset, Uri uri) {
        try (InputStream in = new java.io.FileInputStream(preset.file);
             java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("Could not open output stream");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to export user preset " + preset.name, e);
            Toast.makeText(this, R.string.preset_export_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.preset_exported_toast, preset.name),
                Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteUserPreset(UserPresets.UserPreset preset) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_delete_title)
                .setMessage(getString(R.string.preset_delete_message, preset.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preset_delete_confirm, (d, w) -> {
                    if (UserPresets.delete(preset)) {
                        Toast.makeText(this,
                                getString(R.string.preset_deleted_toast, preset.name),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.preset_delete_failed,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    /**
     * Reusable "enter preset name" dialog backed by a single-line EditText. The {@code onOk}
     * callback fires only when the trimmed input is non-empty.
     */
    private void showPresetNameDialog(int titleRes, int confirmRes, String initialValue,
                                      NameCallback onOk) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.preset_name_hint);
        input.setText(initialValue);
        input.setSelection(input.getText().length());
        int pad = getResources().getDimensionPixelSize(R.dimen.optionsMargin);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        frame.addView(input, lp);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(confirmRes, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.preset_name_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    onOk.onName(name);
                })
                .show();
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
            startActivityForResult(intent, BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE);
            return;
        }

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
    }

    public void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
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
