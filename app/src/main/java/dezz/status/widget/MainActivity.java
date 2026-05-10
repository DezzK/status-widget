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
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    public static final int FOREGROUND_PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;
    public static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1003;
    public static final int BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE = 1004;

    private static final String EXPORT_FILE_NAME = "status-widget-settings.json";
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

        binding.toolbar.setOnMenuItemClickListener(this::onMenuItemSelected);

        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.toolbar.setSubtitle(appVersion);
        }

        initializeViews();

        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startWidgetService();
        }
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return windowInsets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    private boolean onMenuItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        if (id == R.id.menu_export_settings) {
            exportSettings();
            return true;
        }
        if (id == R.id.menu_import_settings) {
            importLauncher.launch(new String[]{EXPORT_MIME_TYPE, "*/*"});
            return true;
        }
        return false;
    }

    private void exportSettings() {
        try {
            String json = prefs.exportToJson();
            File exportsDir = new File(getCacheDir(), "exports");
            if (!exportsDir.exists() && !exportsDir.mkdirs()) {
                Toast.makeText(this, R.string.export_failed_toast, Toast.LENGTH_LONG).show();
                return;
            }
            File file = new File(exportsDir, EXPORT_FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(EXPORT_MIME_TYPE);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, EXPORT_FILE_NAME);
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
                binding.sectionContent.calendarAlignmentDropdown,
                R.array.calendar_alignment_types,
                prefs.calendarAlignment);

        ViewBinder binder = new ViewBinder(this);

        binder.bindCheckbox(binding.sectionContent.showDateSwitch, prefs.showDate);
        binder.bindCheckbox(binding.sectionContent.showTimeSwitch, prefs.showTime);
        binder.bindCheckbox(binding.sectionContent.showDaySwitch, prefs.showDayOfTheWeek);
        binder.bindCheckbox(binding.sectionContent.showWiFiSwitch, prefs.showWifiIcon);
        binder.bindCheckbox(binding.sectionContent.showGnssSwitch, prefs.showGnssIcon);
        binder.bindCheckbox(binding.sectionContent.showGnssSatelliteBadgeSwitch, prefs.showGnssSatelliteBadge);
        binder.bindCheckbox(binding.sectionContent.showFullDayAndMonthSwitch, prefs.showFullDayAndMonth);
        binder.bindCheckbox(binding.sectionContent.dateBeforeDayOfWeekSwitch, prefs.dateBeforeDayOfWeek);
        binder.bindCheckbox(binding.sectionContent.oneLineLayoutSwitch, prefs.oneLineLayout);

        binder.bindSizeSlider(binding.sectionSizes.iconSizeSlider, prefs.iconSize);
        binder.bindSizeSlider(binding.sectionSizes.timeFontSizeSlider, prefs.timeFontSize);
        binder.bindSizeSlider(binding.sectionSizes.dateFontSizeSlider, prefs.dateFontSize);
        binder.bindSizeSlider(binding.sectionSizes.spacingBetweenTextsAndIconsSlider, prefs.spacingBetweenTextsAndIcons);
        binder.bindOffsetSlider(binding.sectionSizes.adjustTimeYSlider, prefs.adjustTimeY);
        binder.bindOffsetSlider(binding.sectionSizes.adjustDateYSlider, prefs.adjustDateY);

        binder.bindColorComponentSlider(binding.sectionAppearance.textOutlineAlphaSlider, prefs.textOutlineAlpha);
        binder.bindSizeSlider(binding.sectionAppearance.textOutlineWidthSlider, prefs.textOutlineWidth);
        binder.bindColorComponentSlider(binding.sectionAppearance.iconOutlineAlphaSlider, prefs.iconOutlineAlpha);
        binder.bindSizeSlider(binding.sectionAppearance.iconOutlineWidthSlider, prefs.iconOutlineWidth);
        binder.bindColorComponentSlider(binding.sectionAppearance.backgroundAlphaSlider, prefs.backgroundAlpha);
        binder.bindPercentSlider(binding.sectionAppearance.backgroundCornerRadiusSlider, prefs.backgroundCornerRadius);

        binding.sectionGeneral.hideInAppsButton.setOnClickListener(v -> openAppSelection());
    }

    private void bindDropdown(MaterialAutoCompleteTextView dropdown, int arrayRes, Preferences.Int preference) {
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
        });
    }

    private void openAppSelection() {
        if (!Permissions.isUsageAccessGranted(this)) {
            Toast.makeText(this, R.string.usage_access_required, Toast.LENGTH_LONG).show();
            openUsageAccessSettings();
            return;
        }
        startActivity(new Intent(this, AppSelectionActivity.class));
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
