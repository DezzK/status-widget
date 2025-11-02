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
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WidgetService extends Service {
    enum GnssState {
        OFF,
        BAD,
        GOOD
    }

    enum WiFiState {
        OFF,
        DISCONNECTED,
        CONNECTED
    }

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_STATUS_CHECK_INTERVAL = 1000;

    private static WidgetService instance;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View overlayView;
    private ImageView wifiStatusIcon;
    private ImageView gnssStatusIcon;
    private TextView dateTimeText;

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;

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
                updateGnssStatus(GnssState.OFF);
            } else if (System.currentTimeMillis() - lastLocationUpdateTime > 5000) {
                updateGnssStatus(GnssState.BAD);
            }

            mainHandler.postDelayed(this, GNSS_STATUS_CHECK_INTERVAL);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            updateGnssStatus(GnssState.BAD);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            updateGnssStatus(GnssState.OFF);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            updateGnssStatus(GnssState.BAD);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(TAG, "Location changed: " + location);
            lastLocationUpdateTime = System.currentTimeMillis();
            if (location.hasAccuracy() && location.getAccuracy() < 5.0) {
                updateGnssStatus(GnssState.GOOD);
            } else {
                updateGnssStatus(GnssState.BAD);
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
            // TODO: Change this to use the actual state of the Wi-Fi connection
            updateWifiStatus(WiFiState.DISCONNECTED);
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is lost");
            updateWifiStatus(WiFiState.OFF);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, NetworkCapabilities networkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                boolean hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

                // TODO: Change this to use the actual state of the Wi-Fi connection
                if (hasInternet) {
                    updateWifiStatus(WiFiState.CONNECTED);
                } else {
                    updateWifiStatus(WiFiState.DISCONNECTED);
                }

                Log.d(TAG, "Wi-Fi capabilities changed, has internet = " + hasInternet);
            }
        }
    };

    @SuppressLint({"MissingPermission", "InflateParams"})
    @Override
    public void onCreate() {
        if (!Permissions.allPermissionsGranted(this)) {
            Preferences.saveWidgetEnabled(this, false);
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            startMainActivity();
            stopSelf();
            return;
        }

        instance = this;

        windowManager = getSystemService(WindowManager.class);
        locationManager = getSystemService(LocationManager.class);
        connectivityManager = getSystemService(ConnectivityManager.class);

        // Create the overlay view
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_status_widget, null);
        overlayView.setVisibility(View.VISIBLE);

        // Initialize controls
        wifiStatusIcon = overlayView.findViewById(R.id.wifiStatusIcon);
        gnssStatusIcon = overlayView.findViewById(R.id.gnssStatusIcon);
        dateTimeText = overlayView.findViewById(R.id.dateTimeText);

        applyPreferences();

        // Set initial states
        updateWifiStatus(WiFiState.OFF);
        updateGnssStatus(GnssState.OFF);

        // Set up drag listener
        setupDragListener();

        // Add the view to the window
        Point position = Preferences.overlayPosition(this);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = position.x;
        params.y = position.y;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // Register status receivers
        locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);

        locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                3000,
                0,
                locationListener,
                Looper.getMainLooper()
        );

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        mainHandler.postDelayed(updateGnssStatusRunnable, GNSS_STATUS_CHECK_INTERVAL);
    }

    public void applyPreferences() {
        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();

        int scaledIconSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, (float) Preferences.iconSize(this), displayMetrics);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                scaledIconSize,
                scaledIconSize
        );
        wifiStatusIcon.setLayoutParams(iconParams);
        gnssStatusIcon.setLayoutParams(iconParams);
        dateTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, Preferences.fontSize(this));
        dateTimeText.setVisibility((Preferences.showDate(this) || Preferences.showTime(this)) ? View.VISIBLE : View.GONE);

        updateDateTime();

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (Preferences.showDate(this) || Preferences.showTime(this)) {
            mainHandler.postDelayed(updateDateTimeRunnable, 1000);
        }
    }

    private void updateDateTime() {
        boolean showDate = Preferences.showDate(this);
        boolean showTime = Preferences.showTime(this);
        String format = (showTime ? "HH:mm" + (showDate ? ", " : "") : "") + (showDate ? "d MMM" : "");
        dateTimeText.setText(new SimpleDateFormat(format, Locale.getDefault()).format(new Date()));
        dateTimeText.requestLayout();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragListener() {
        overlayView.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlayView.getLayoutParams();

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
                    windowManager.updateViewLayout(overlayView, params);
                    return true;

                case MotionEvent.ACTION_UP:
                    savePosition();

                    // Handle click if needed
                    if (Math.abs(event.getRawX() - initialTouchX) < 5 &&
                            Math.abs(event.getRawY() - initialTouchY) < 5) {
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

    private void updateWifiStatus(WiFiState status) {
        wifiStatusIcon.setImageResource(switch (status) {
            case WiFiState.OFF -> R.drawable.ic_wifi_off;
            case WiFiState.DISCONNECTED -> R.drawable.ic_wifi_disconnected;
            case WiFiState.CONNECTED -> R.drawable.ic_wifi_connected;
        });
    }

    private void updateGnssStatus(GnssState status) {
        gnssStatusIcon.setImageResource(switch (status) {
            case OFF -> R.drawable.ic_gps_off;
            case BAD -> R.drawable.ic_gps_bad;
            case GOOD -> R.drawable.ic_gps_good;
        });
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_title),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_content))
                .setSmallIcon(R.drawable.ic_gps_good)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    // Add this method to save position
    private void savePosition() {
        if (params != null) {
            Preferences.saveOverlayPosition(this, params.x, params.y);
        }
    }


    @Override
    public void onDestroy() {
        instance = null;

        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
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
}
