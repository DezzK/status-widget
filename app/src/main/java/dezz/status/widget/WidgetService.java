/*
 * Copyright 2025 Dezz (https://github.com/DezzK)
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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

public class WidgetService extends Service {
    enum GnssState {
        OFF, BAD, GOOD
    }

    enum WiFiState {
        OFF, NO_INTERNET, INTERNET
    }

    private static final int[] GNSS_ICONS_MONO = {
            R.drawable.ic_mono_gps_off,
            R.drawable.ic_mono_gps_bad,
            R.drawable.ic_mono_gps_good
    };
    private static final int[] WIFI_ICONS_MONO = {
            R.drawable.ic_mono_wifi_off,
            R.drawable.ic_mono_wifi_no_internet,
            R.drawable.ic_mono_wifi_internet
    };

    private static final int[] GNSS_ICONS_COLOR = {
            R.drawable.ic_color_gps_off,
            R.drawable.ic_color_gps_bad,
            R.drawable.ic_color_gps_good
    };
    private static final int[] WIFI_ICONS_COLOR = {
            R.drawable.ic_color_wifi_off,
            R.drawable.ic_color_wifi_no_internet,
            R.drawable.ic_color_wifi_internet
    };

    private static final int[] GNSS_ICONS_MONOCOLOR = {
            R.drawable.ic_monocolor_gps_off,
            R.drawable.ic_monocolor_gps_bad,
            R.drawable.ic_monocolor_gps_good
    };
    private static final int[] WIFI_ICONS_MONOCOLOR = {
            R.drawable.ic_monocolor_wifi_off,
            R.drawable.ic_monocolor_wifi_no_internet,
            R.drawable.ic_monocolor_wifi_internet
    };

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_STATUS_CHECK_INTERVAL = 1000;

    private static WidgetService instance;

    private Preferences prefs;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private GnssState gnssState = GnssState.OFF;
    private WiFiState wifiState = WiFiState.OFF;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager = null;
    private ConnectivityManager connectivityManager = null;
    private long lastLocationUpdateTime = 0;

    private final Runnable updateDateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable updateGnssStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastLocationUpdateTime > 10000) {
                setGnssStatus(GnssState.OFF);
            } else if (System.currentTimeMillis() - lastLocationUpdateTime > 5000) {
                setGnssStatus(GnssState.BAD);
            }

            mainHandler.postDelayed(this, GNSS_STATUS_CHECK_INTERVAL);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            setGnssStatus(GnssState.BAD);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            setGnssStatus(GnssState.OFF);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            setGnssStatus(GnssState.BAD);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(TAG, "Location changed: " + location);
            lastLocationUpdateTime = System.currentTimeMillis();
            if (location.hasAccuracy() && location.getAccuracy() < 10.0) {
                setGnssStatus(GnssState.GOOD);
            } else {
                setGnssStatus(GnssState.BAD);
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is connected");
            if (wifiState == WiFiState.OFF) {
                setWifiStatus(WiFiState.NO_INTERNET);
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is lost");
            setWifiStatus(WiFiState.OFF);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, NetworkCapabilities networkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                boolean hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                Log.d(TAG, "Wi-Fi capabilities changed, has internet = " + hasInternet);
                setWifiStatus(hasInternet ? WiFiState.INTERNET : WiFiState.NO_INTERNET);
            } else {
                setWifiStatus(WiFiState.OFF);
            }
        }
    };

    @Override
    public void onCreate() {
        prefs = new Preferences(this);
        if (!Permissions.allPermissionsGranted(this)) {
            prefs.widgetEnabled.set(false);
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            startMainActivity();
            stopSelf();
            return;
        }

        instance = this;

        windowManager = getSystemService(WindowManager.class);

        createOverlayView();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    private void createOverlayView() {
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        binding.getRoot().setVisibility(View.VISIBLE);

        applyPreferences();

        updateWifiStatus();
        updateGnssStatus();

        // Set up drag listener
        setupDragListener();

        // Add the view to the window
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                ,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.overlayX.get();
        params.y = prefs.overlayY.get();

        try {
            windowManager.addView(binding.getRoot(), params);
        } catch (Exception e) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (binding != null) {
            windowManager.removeView(binding.getRoot());
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        updateDateTime();

        int iconSize = prefs.iconSize.get();

        ViewGroup.LayoutParams iconParams = binding.wifiStatusIcon.getLayoutParams();
        iconParams.width = iconSize;
        iconParams.height = iconSize;
        binding.wifiStatusIcon.setLayoutParams(iconParams);

        iconParams = binding.gnssStatusIcon.getLayoutParams();
        iconParams.width = iconSize;
        iconParams.height = iconSize;
        binding.gnssStatusIcon.setLayoutParams(iconParams);

        float timeOutlineWidth = Math.max(2F, prefs.timeFontSize.get() / 32F);
        float dateOutlineWidth = Math.max(2F, prefs.dateFontSize.get() / 32F);
        int outlineColor = ContextCompat.getColor(this, R.color.text_outline) & 0x00FFFFFF | (prefs.textOutlineAlpha.get() << 24);
        binding.timeText.setOutlineColor(outlineColor);
        binding.timeText.setOutlineWidth(timeOutlineWidth);
        binding.dateText.setOutlineColor(outlineColor);
        binding.dateText.setOutlineWidth(dateOutlineWidth);

        binding.timeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.timeFontSize.get());
        binding.dateText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.dateFontSize.get());
        binding.timeText.setVisibility(prefs.showTime.get() ? View.VISIBLE : View.GONE);
        binding.dateText.setVisibility(prefs.showDate.get() || prefs.showDayOfTheWeek.get() ? View.VISIBLE : View.GONE);
        // Calendar alignment
        switch (prefs.calendarAlignment.get()) {
            case 1 -> binding.dateText.setGravity(Gravity.CENTER_HORIZONTAL);
            case 2 -> binding.dateText.setGravity(Gravity.END);
            default -> binding.dateText.setGravity(Gravity.START);
        }
        // Icons (GPS and WiFi)
        binding.wifiStatusIcon.setVisibility(prefs.showWifiIcon.get() ? View.VISIBLE : View.GONE);
        binding.gnssStatusIcon.setVisibility(prefs.showGnssIcon.get() ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams dateTimeLayoutParams = (LinearLayout.LayoutParams) binding.dateTimeContainer.getLayoutParams();
        dateTimeLayoutParams.setMargins(0, 0, prefs.spacingBetweenTextsAndIcons.get(), 0);
        binding.dateTimeContainer.setLayoutParams(dateTimeLayoutParams);

        binding.timeText.setTranslationY(prefs.adjustTimeY.get());
        binding.dateText.setTranslationY(prefs.adjustDateY.get());

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (prefs.showDate.get() || prefs.showTime.get()) {
            mainHandler.postDelayed(updateDateTimeRunnable, 1000);
        }

        if (prefs.showWifiIcon.get()) {
            if (connectivityManager == null) {
                connectivityManager = getSystemService(ConnectivityManager.class);

                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(net);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        setWifiStatus(hasInternet ? WiFiState.INTERNET : WiFiState.NO_INTERNET);
                        break;
                    }
                }

                NetworkRequest networkRequest = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
            }
            updateWifiStatus();
        } else if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            connectivityManager = null;
        }

        if (prefs.showGnssIcon.get()) {
            if (locationManager == null) {
                locationManager = getSystemService(LocationManager.class);

                locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
                mainHandler.postDelayed(updateGnssStatusRunnable, GNSS_STATUS_CHECK_INTERVAL);
            }
            updateGnssStatus();
        } else if (locationManager != null) {
            mainHandler.removeCallbacks(updateGnssStatusRunnable);
            locationManager.removeUpdates(locationListener);
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager = null;
        }
    }

    private void updateDateTime() {
        boolean showTime = prefs.showTime.get();
        boolean showDate = prefs.showDate.get();
        boolean showDayOfTheWeek = prefs.showDayOfTheWeek.get();

        if (!showTime && !showDate && !showDayOfTheWeek) {
            return;
        }

        boolean showFullDayAndMonth = prefs.showFullDayAndMonth.get();

        String divider = (showDate && showDayOfTheWeek) ? (prefs.oneLineLayout.get() ? "," : " \n") : "";
        String dayOfTheWeekFormatStr = showFullDayAndMonth ? "EEEE" : "EEE";
        String dateFormatStr = showFullDayAndMonth ? "d MMMM" : "d MMM";

        // We add spaces at the start/end to avoid outline cropping by canvas which is not ready for the outline
        String fullFormatStr = (showDayOfTheWeek ? " " + dayOfTheWeekFormatStr + divider : "") + (showDate ? " " + dateFormatStr : "") + " ";

        Date now = new Date();
        String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        String dateStr = new SimpleDateFormat(fullFormatStr, Locale.getDefault()).format(now);
        if (showTime && !timeStr.contentEquals(binding.timeText.getText())) {
            binding.timeText.setText(timeStr);
        }
        if ((showDate || showDayOfTheWeek) && !dateStr.contentEquals(binding.dateText.getText())) {
            binding.dateText.setText(dateStr);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragListener() {
        binding.getRoot().setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) binding.getRoot().getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(binding.getRoot(), params);
                    return true;

                case MotionEvent.ACTION_UP:
                    savePosition();

                    // Handle click
                    if (Math.abs(event.getRawX() - initialTouchX) < 5 && Math.abs(event.getRawY() - initialTouchY) < 5) {
                        if (binding.wifiStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.wifiStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);

                            return true;
                        }
                        if (binding.gnssStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.gnssStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage("dezz.gnssshare.client");
                            if (intent == null) {
                                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);

                            return true;
                        }

                        startMainActivity();
                    }
                    return true;
            }
            return false;
        });
    }

    private void startMainActivity() {
        Intent startIntent = new Intent(WidgetService.this, MainActivity.class);
        startIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(startIntent);
    }

    private void setWifiStatus(WiFiState newState) {
        wifiState = newState;
        updateWifiStatus();
    }

    private void updateWifiStatus() {
        updateIconStatus(WIFI_ICONS_MONO, WIFI_ICONS_COLOR, WIFI_ICONS_MONOCOLOR, binding.wifiStatusIcon, wifiState.ordinal());
    }

    private void setGnssStatus(GnssState newState) {
        gnssState = newState;
        updateGnssStatus();
    }

    private void updateGnssStatus() {
        updateIconStatus(GNSS_ICONS_MONO, GNSS_ICONS_COLOR, GNSS_ICONS_MONOCOLOR, binding.gnssStatusIcon, gnssState.ordinal());
    }

    private void updateIconStatus(int[] monoResources, int[] colorResources, int[] monocolorResources, ImageView icon, int state) {
        switch (prefs.iconStyle.get()) {
            case 0 -> icon.setImageResource(monoResources[state]);
            case 1 -> icon.setImageResource(colorResources[state]);
            case 2 -> icon.setImageResource(monocolorResources[state]);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_title), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_mono_gps_good).setContentIntent(pendingIntent).setOngoing(true).build();
    }

    // Add this method to save position
    private void savePosition() {
        if (params != null) {
            prefs.overlayX.set(params.x);
            prefs.overlayY.set(params.y);
        }
    }


    @Override
    public void onDestroy() {
        instance = null;

        mainHandler.removeCallbacks(updateGnssStatusRunnable);
        mainHandler.removeCallbacks(updateDateTimeRunnable);

        if (binding != null && windowManager != null) {
            windowManager.removeView(binding.getRoot());
        }

        if (locationManager != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager.removeUpdates(locationListener);
        }

        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static WidgetService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private static Rect getBounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
