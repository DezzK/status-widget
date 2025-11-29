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

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.ActivityCompat;

import java.util.List;

import dezz.status.widget.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static final int PERMISSION_REQUEST_CODE = 1001;
    public static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;

    private Preferences prefs;

    ActivityMainBinding binding;

    // Создаём themed контекст
    Context themedContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new Preferences(this);
        super.onCreate(savedInstanceState);
        // Вместо AppCompatDelegate.setDefaultNightMode() используем определенную тему.
        // Оборачиваем контекст в тему, чтобы ?attr работали.
        themedContext = new ContextThemeWrapper(this, getThemeResId());
        LayoutInflater layoutInflater = LayoutInflater.from(themedContext);
        binding = ActivityMainBinding.inflate(layoutInflater);
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
        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.headerText.setText(String.format("%s %s", getString(R.string.app_name), appVersion));
        }
        binding.copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

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

        ArrayAdapter<String> iconStylesAdapter = new ArrayAdapter<>(
                themedContext,
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

        /* Выбор темы */
        ArrayAdapter<String> themeSpinnerAdapter = new ArrayAdapter<>(
                themedContext,
                R.layout.spinner_dropdown_item,
                getResources().getStringArray(R.array.night_mode_types)
        );
        themeSpinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.themeSpinner.setAdapter(themeSpinnerAdapter);
        binding.themeSpinner.setSelection(prefs.nightModeSpinnerOption.get());
        binding.themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != prefs.nightModeSpinnerOption.get()) {
                    saveNightModeAndRestart(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        /* Выбор выравнивания даты */
        ArrayAdapter<String> calendarAlignAdapter = new ArrayAdapter<>(
                themedContext,
                R.layout.spinner_dropdown_item,
                getResources().getStringArray(R.array.calendar_align_types)
        );
        calendarAlignAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.calendarAlignSpinner.setAdapter(calendarAlignAdapter);
        binding.calendarAlignSpinner.setSelection(prefs.calendarAlign.get());
        binding.calendarAlignSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.calendarAlign.set(position);
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


    /* Вызывается при смене опции ночного режима в спинере */
    private void saveNightModeAndRestart(int themeSpinnerOption) {
        Log.d(TAG, "Saving NightMode spinner option: " + themeSpinnerOption + " and restarting activity");
        prefs.nightModeSpinnerOption.set(themeSpinnerOption);

        switch (themeSpinnerOption) {
            case 1:
                prefs.savedNightMode.set(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                prefs.savedNightMode.set(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 0:
            default:
                prefs.savedNightMode.set(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
        // Уведомляем WidgetService о смене темы
        Intent themeChangedIntent = new Intent("ACTION_THEME_CHANGED");
        sendBroadcast(themeChangedIntent);

        // Перезапуск Activity для смены темы у UI настроек
        Log.d(TAG, "Restarting activity...");
        recreate(); // вызовет onCreate()
    }

    private int getThemeResId() {
        int nightMode = prefs.savedNightMode.get();
        boolean isSystemInNightMode =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES ||
                (nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && isSystemInNightMode)) {
            return R.style.AppTheme_Night; // Night theme style
        } else {
            return R.style.AppTheme_Day; // Day theme style
        }
    }

}
