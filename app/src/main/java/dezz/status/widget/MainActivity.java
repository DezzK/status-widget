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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;

import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    public static final int PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;

    private interface ValueTextFormatter {
        String formatValueText(int progress);
    }

    private Preferences prefs;

    ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();

        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startWidgetService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initializeViews() {
        TextView copyrightNoticeText = findViewById(R.id.copyrightNoticeText);
        copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

        binding.enableWidgetSwitch.setChecked(prefs.widgetEnabled.get());
        binding.enableWidgetSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
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
        });

        bindCheckbox(binding.useColorIconsSwitch, prefs.useColorIcons);
        bindCheckbox(binding.showDateSwitch, prefs.showDate);
        bindCheckbox(binding.showTimeSwitch, prefs.showTime);
        bindCheckbox(binding.showDaySwitch, prefs.showDayOfTheWeek);
        bindCheckbox(binding.showWiFiSwitch, prefs.showWifiIcon);
        bindCheckbox(binding.showGnssSwitch, prefs.showGnssIcon);
        bindCheckbox(binding.showFullDayAndMonthSwitch, prefs.showFullDayAndMonth);
        bindCheckbox(binding.oneLineLayoutSwitch, prefs.oneLineLayout);

        bindSizeSeekbar(binding.iconSizeSeekBar, binding.iconSizeValueText, prefs.iconSize);
        bindSizeSeekbar(binding.timeFontSizeSeekBar, binding.timeFontSizeValueText, prefs.timeFontSize);
        bindSizeSeekbar(binding.dateFontSizeSeekBar, binding.dateFontSizeValueText, prefs.dateFontSize);
        bindSizeSeekbar(binding.spacingBetweenTextsAndIconsSeekBar, binding.spacingBetweenTextsAndIconsValueText, prefs.spacingBetweenTextsAndIcons);
        bindOffsetSeekbar(binding.adjustTimeYSeekBar, binding.adjustTimeYValueText, prefs.adjustTimeY);
        bindOffsetSeekbar(binding.adjustDateYSeekBar, binding.adjustDateYValueText, prefs.adjustDateY);
    }

    private void startWidgetService() {
        prefs.widgetEnabled.set(true);
        startForegroundService(new Intent(this, WidgetService.class));
    }

    private void stopWidgetService() {
        prefs.widgetEnabled.set(false);
        stopService(new Intent(this, WidgetService.class));
    }

    private void bindCheckbox(SwitchCompat checkbox, Preferences.Bool preference) {
        checkbox.setChecked(preference.get());
        checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preference.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    private void bindSizeSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format(getString(R.string.size_value_format), value));
    }

    private void bindOffsetSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format((value > 0 ? "+" : "") + getString(R.string.size_value_format), value));
    }

    private void bindSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference, ValueTextFormatter formatter) {
        int progress = preference.get();
        seekBar.setProgress(progress);
        valueText.setText(formatter.formatValueText(progress));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                preference.set(progress);
                valueText.setText(formatter.formatValueText(progress));
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = Permissions.checkForMissingPermissions(this);

        if (!permissionsToRequest.isEmpty()) {
            // Request missing permissions
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else if (!Permissions.checkOverlayPermission(this)) {
            requestOverlayPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!Permissions.checkForMissingPermissions(this).isEmpty()) {
                binding.enableWidgetSwitch.setChecked(false);
                Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
            } else if (!Permissions.checkOverlayPermission(this)) {
                requestOverlayPermission();
            } else {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                startWidgetService();
            }
        }
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
                binding.enableWidgetSwitch.setChecked(false);
                Toast.makeText(this, R.string.overlay_permission_required,
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
