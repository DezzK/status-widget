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
import androidx.core.app.ActivityCompat;

import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static final int PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;

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
        binding.useColorIconsSwitch.setChecked(prefs.useColorIcons.get());
        binding.showDateSwitch.setChecked(prefs.showDate.get());
        binding.showTimeSwitch.setChecked(prefs.showTime.get());
        binding.showDaySwitch.setChecked(prefs.showDayOfTheWeek.get());
        binding.showWiFiSwitch.setChecked(prefs.showWifiIcon.get());
        binding.showGnssSwitch.setChecked(prefs.showGnssIcon.get());
        binding.showFullDayAndMonthSwitch.setChecked(prefs.showFullDayAndMonth.get());
        binding.oneLineLayoutSwitch.setChecked(prefs.oneLineLayout.get());
        binding.iconSizeSeekBar.setProgress(prefs.iconSize.get());
        binding.timeFontSizeSeekBar.setProgress(prefs.timeFontSize.get());
        binding.dateFontSizeSeekBar.setProgress(prefs.dateFontSize.get());
        binding.spacingBetweenTextsAndIconsSeekBar.setProgress(prefs.spacingBetweenTextsAndIcons.get());
        binding.adjustTimeYSeekBar.setProgress(prefs.adjustTimeY.get());
        binding.adjustDateYSeekBar.setProgress(prefs.adjustDateY.get());

        updateIconSizeValueText();
        updateTimeFontSizeValueText();
        updateDateFontSizeValueText();
        updateSpacingBetweenTextsAndIconsValueText();
        updateAdjustTimeYValueText();
        updateAdjustDateYValueText();

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

        binding.useColorIconsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.useColorIcons.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showDateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showDate.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showTimeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showTime.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showDaySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showDayOfTheWeek.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showWiFiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showWifiIcon.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showGnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showGnssIcon.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.showFullDayAndMonthSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.showFullDayAndMonth.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.oneLineLayoutSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.oneLineLayout.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        binding.iconSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.iconSize.set(progress);
                updateIconSizeValueText();
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.timeFontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.timeFontSize.set(progress);
                updateTimeFontSizeValueText();
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.dateFontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.dateFontSize.set(progress);
                updateDateFontSizeValueText();
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.spacingBetweenTextsAndIconsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.spacingBetweenTextsAndIcons.set(progress);
                updateSpacingBetweenTextsAndIconsValueText();
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.adjustTimeYSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.adjustTimeY.set(progress);
                updateAdjustTimeYValueText();
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.adjustDateYSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.adjustDateY.set(progress);
                updateAdjustDateYValueText();
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

    private void updateIconSizeValueText() {
        binding.iconSizeValueText.setText(String.format(getString(R.string.size_value_format), binding.iconSizeSeekBar.getProgress()));
    }

    private void updateTimeFontSizeValueText() {
        binding.timeFontSizeValueText.setText(String.format(getString(R.string.size_value_format), binding.timeFontSizeSeekBar.getProgress()));
    }

    private void updateDateFontSizeValueText() {
        binding.dateFontSizeValueText.setText(String.format(getString(R.string.size_value_format), binding.dateFontSizeSeekBar.getProgress()));
    }

    private void updateSpacingBetweenTextsAndIconsValueText() {
        binding.spacingBetweenTextsAndIconsValueText.setText(String.format(getString(R.string.size_value_format), binding.spacingBetweenTextsAndIconsSeekBar.getProgress()));
    }

    private void updateAdjustTimeYValueText() {
        int value = binding.adjustTimeYSeekBar.getProgress();
        binding.adjustTimeYValueText.setText(String.format((value > 0 ? "+" : "") + getString(R.string.size_value_format), value));
    }

    private void updateAdjustDateYValueText() {
        int value = binding.adjustDateYSeekBar.getProgress();
        binding.adjustDateYValueText.setText(String.format((value > 0 ? "+" : "") + getString(R.string.size_value_format), value));
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
