/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
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
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    public static final int FOREGROUND_PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;
    public static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1003;
    public static final int BACKGROUND_LOCATION_SETTINGS_REQUEST_CODE = 1004;

    private Preferences prefs;

    ActivityMainBinding binding;

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
        binding.enableWidgetSwitch.setOnCheckedChangeListener(null);
        binding.enableWidgetSwitch.setChecked(false);
        binding.enableWidgetSwitch.setOnCheckedChangeListener(enableWidgetSwitchListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);

        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();

        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startWidgetService();
        }
    }

    private void initializeViews() {
        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.headerText.setText(String.format("%s %s", getString(R.string.app_name), appVersion));
        }
        binding.copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

        binding.enableWidgetSwitch.setChecked(prefs.widgetEnabled.get());
        binding.enableWidgetSwitch.setOnCheckedChangeListener(enableWidgetSwitchListener);

        ArrayAdapter<String> iconStylesAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_dropdown_item,
                getResources().getStringArray(R.array.icon_styles)
        );
        iconStylesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.iconStyleSpinner.setAdapter(iconStylesAdapter);
        binding.iconStyleSpinner.setSelection(prefs.iconStyle.get());
        binding.iconStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.iconStyle.set(position);
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Calendar alignment dropdown
        ArrayAdapter<String> calendarAlignmentAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_dropdown_item,
                getResources().getStringArray(R.array.calendar_alignment_types)
        );
        calendarAlignmentAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.calendarAlignmentSpinner.setAdapter(calendarAlignmentAdapter);
        binding.calendarAlignmentSpinner.setSelection(prefs.calendarAlignment.get());
        binding.calendarAlignmentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.calendarAlignment.set(position);
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ViewBinder binder = new ViewBinder(this);

        binder.bindCheckbox(binding.showDateSwitch, prefs.showDate);
        binder.bindCheckbox(binding.showTimeSwitch, prefs.showTime);
        binder.bindCheckbox(binding.showDaySwitch, prefs.showDayOfTheWeek);
        binder.bindCheckbox(binding.showWiFiSwitch, prefs.showWifiIcon);
        binder.bindCheckbox(binding.showGnssSwitch, prefs.showGnssIcon);
        binder.bindCheckbox(binding.showFullDayAndMonthSwitch, prefs.showFullDayAndMonth);
        binder.bindCheckbox(binding.oneLineLayoutSwitch, prefs.oneLineLayout);

        binder.bindSizeSeekbar(binding.iconSizeSeekBar, binding.iconSizeValueText, prefs.iconSize);
        binder.bindSizeSeekbar(binding.timeFontSizeSeekBar, binding.timeFontSizeValueText, prefs.timeFontSize);
        binder.bindSizeSeekbar(binding.dateFontSizeSeekBar, binding.dateFontSizeValueText, prefs.dateFontSize);
        binder.bindSizeSeekbar(binding.spacingBetweenTextsAndIconsSeekBar, binding.spacingBetweenTextsAndIconsValueText, prefs.spacingBetweenTextsAndIcons);
        binder.bindColorComponentSeekbar(binding.textOutlineAlphaSeekBar, binding.textOutlineAlphaValueText, prefs.textOutlineAlpha);
        binder.bindColorComponentSeekbar(binding.backgroundAlphaSeekBar, binding.backgroundAlphaValueText, prefs.backgroundAlpha);
        binder.bindPercentSeekbar(binding.backgroundCornerRadiusSeekBar, binding.backgroundCornerRadiusValueText, prefs.backgroundCornerRadius);
        binder.bindOffsetSeekbar(binding.adjustTimeYSeekBar, binding.adjustTimeYValueText, prefs.adjustTimeY);
        binder.bindOffsetSeekbar(binding.adjustDateYSeekBar, binding.adjustDateYValueText, prefs.adjustDateY);
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

        // On Android 11+ the system no longer shows a dialog for ACCESS_BACKGROUND_LOCATION via
        // requestPermissions(); the user must grant "Allow all the time" in app settings.
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
