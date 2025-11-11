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
import android.text.method.MovementMethod;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static final int PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;

    private SwitchCompat enableWidgetSwitch;
    private SeekBar iconSizeSeekBar;
    private SeekBar timeFontSizeSeekBar;
    private SeekBar dateFontSizeSeekBar;
    private SeekBar spacingBetweenTextsAndIconsSeekBar;
    private TextView iconSizeValueText;
    private TextView timeFontSizeValueText;
    private TextView dateFontSizeValueText;
    private TextView spacingBetweenTextsAndIconsValueText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);

        initializeViews();

        if (Preferences.widgetEnabled(this) && Permissions.allPermissionsGranted(this)) {
            startWidgetService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initializeViews() {
        enableWidgetSwitch = findViewById(R.id.enableWidgetSwitch);
        SwitchCompat showDateSwitch = findViewById(R.id.showDateSwitch);
        SwitchCompat showTimeSwitch = findViewById(R.id.showTimeSwitch);
        SwitchCompat showDayOfTheWeekSwitch = findViewById(R.id.showDaySwitch);
        SwitchCompat showWifiIconSwitch = findViewById(R.id.showWiFiSwitch);
        SwitchCompat showGnssIconSwitch = findViewById(R.id.showGnssSwitch);
        SwitchCompat showFullDayAndMonthSwitch = findViewById(R.id.showFullDayAndMonthSwitch);
        SwitchCompat oneLineLayoutSwitch = findViewById(R.id.oneLineLayoutSwitch);

        iconSizeSeekBar = findViewById(R.id.iconSizeSeekBar);
        timeFontSizeSeekBar = findViewById(R.id.timeFontSizeSeekBar);
        dateFontSizeSeekBar = findViewById(R.id.dateFontSizeSeekBar);
        spacingBetweenTextsAndIconsSeekBar = findViewById(R.id.spacingBetweenTextsAndIconsSeekBar);

        iconSizeValueText = findViewById(R.id.iconSizeValueText);
        timeFontSizeValueText = findViewById(R.id.timeFontSizeValueText);
        dateFontSizeValueText = findViewById(R.id.dateFontSizeValueText);
        spacingBetweenTextsAndIconsValueText = findViewById(R.id.spacingBetweenTextsAndIconsValueText);

        TextView copyrightNoticeText = findViewById(R.id.copyrightNoticeText);
        copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

        enableWidgetSwitch.setChecked(Preferences.widgetEnabled(this));
        showDateSwitch.setChecked(Preferences.showDate(this));
        showTimeSwitch.setChecked(Preferences.showTime(this));
        showDayOfTheWeekSwitch.setChecked(Preferences.showDayOfTheWeek(this));
        showWifiIconSwitch.setChecked(Preferences.showWifiIcon(this));
        showGnssIconSwitch.setChecked(Preferences.showGnssIcon(this));
        showFullDayAndMonthSwitch.setChecked(Preferences.showFullDayAndMonth(this));
        oneLineLayoutSwitch.setChecked(Preferences.oneLineLayout(this));
        iconSizeSeekBar.setProgress(Preferences.iconSize(this));
        timeFontSizeSeekBar.setProgress(Preferences.timeFontSize(this));
        dateFontSizeSeekBar.setProgress(Preferences.dateFontSize(this));
        spacingBetweenTextsAndIconsSeekBar.setProgress(Preferences.spacingBetweenTextsAndIcons(this));

        updateIconSizeValueText();
        updateTimeFontSizeValueText();
        updateDateFontSizeValueText();
        updateSpacingBetweenTextsAndIconsValueText();

        enableWidgetSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (Permissions.allPermissionsGranted(this)) {
                    startWidgetService();
                } else {
                    requestPermissions();
                }
            } else {
                stopWidgetService();
                Preferences.resetOverlayPosition(this);
            }
        });

        showDateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowDate(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        showTimeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowTime(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        showDayOfTheWeekSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowDayOfTheWeek(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        showWifiIconSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowWifiIcon(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        showGnssIconSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowGnssIcon(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        showFullDayAndMonthSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveShowFullDayAndMonth(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        oneLineLayoutSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Preferences.saveOneLineLayout(this, isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });

        iconSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Preferences.saveIconSize(MainActivity.this, progress);
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

        timeFontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Preferences.saveTimeFontSize(MainActivity.this, progress);
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

        dateFontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Preferences.saveDateFontSize(MainActivity.this, progress);
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

        spacingBetweenTextsAndIconsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Preferences.saveSpacingBetweenTextsAndIcons(MainActivity.this, progress);
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
    }

    private void updateIconSizeValueText() {
        iconSizeValueText.setText(String.format(getString(R.string.size_value_format), iconSizeSeekBar.getProgress()));
    }

    private void updateTimeFontSizeValueText() {
        timeFontSizeValueText.setText(String.format(getString(R.string.size_value_format), timeFontSizeSeekBar.getProgress()));
    }

    private void updateDateFontSizeValueText() {
        dateFontSizeValueText.setText(String.format(getString(R.string.size_value_format), dateFontSizeSeekBar.getProgress()));
    }

    private void updateSpacingBetweenTextsAndIconsValueText() {
        spacingBetweenTextsAndIconsValueText.setText(String.format(getString(R.string.size_value_format), spacingBetweenTextsAndIconsSeekBar.getProgress()));
    }

    private void startWidgetService() {
        Preferences.saveWidgetEnabled(this, true);
        startForegroundService(new Intent(this, WidgetService.class));
    }

    private void stopWidgetService() {
        Preferences.saveWidgetEnabled(this, false);
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
                enableWidgetSwitch.setChecked(false);
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
                enableWidgetSwitch.setChecked(false);
                Toast.makeText(this, R.string.overlay_permission_required,
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
