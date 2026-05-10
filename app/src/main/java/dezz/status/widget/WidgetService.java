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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

public class WidgetService extends Service {
    enum GnssState {
        OFF, BAD, GOOD
    }

    enum WiFiState {
        OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET
    }

    // Icon designs: 4 Wi-Fi states (OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET) and 3 GNSS states.
    private static final int[][] DESIGN_CLASSIC = {
            {
                    R.drawable.ic_status_wifi_off,
                    R.drawable.ic_status_wifi_no_internet,
                    R.drawable.ic_status_wifi_whitelist,
                    R.drawable.ic_status_wifi_internet
            },
            { R.drawable.ic_status_gps_off, R.drawable.ic_status_gps_bad, R.drawable.ic_status_gps_good }
    };
    private static final int[][] DESIGN_SOLID = {
            {
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            { R.drawable.ic_status_filled_gps_off, R.drawable.ic_status_filled_gps_bad, R.drawable.ic_status_filled_gps_good }
    };
    private static final int[][] DESIGN_BARS = {
            {
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            },
            { R.drawable.ic_status_bars_gps_off, R.drawable.ic_status_bars_gps_bad, R.drawable.ic_status_bars_gps_good }
    };
    private static final int[][][] ICON_DESIGNS = { DESIGN_CLASSIC, DESIGN_SOLID, DESIGN_BARS };

    private static final int ICON_TYPE_WIFI = 0;
    private static final int ICON_TYPE_GNSS = 1;

    // Icon style indices (must match strings.xml/icon_styles array order).
    private static final int STYLE_MONO = 0;
    private static final int STYLE_COLOR = 1;

    private static final long INTERNET_PROBE_INTERVAL_MS = 30_000L;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_STATUS_CHECK_INTERVAL = 1000;
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 1000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
    private static final String GNSSSHARE_CLIENT_PACKAGE = "dezz.gnssshare.client";
    private static final String GNSSSHARE_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    private static final String GNSSSHARE_EXTRA_SATELLITES_COUNT = "count";
    private static final long GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS = 30_000L;

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

    private GradientDrawable background = null;
    private int bgColor = -1;
    private int bgCornerRadius = -1;

    private int touchSlop;

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private String currentDateFormatPattern;

    private UsageStatsManager usageStatsManager = null;
    private Set<String> hiddenInPackages;
    private String lastForegroundPackage;
    private boolean overlayHiddenByApp = false;

    private int satellitesCount = 0;
    private long satellitesCountTimestamp = 0;
    private boolean satelliteReceiverRegistered = false;
    private final BroadcastReceiver satelliteStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra(GNSSSHARE_EXTRA_SATELLITES_COUNT, 0);
            Log.d(TAG, "GNSS Share satellites count: " + count);
            satellitesCount = count;
            satellitesCountTimestamp = System.currentTimeMillis();
            mainHandler.removeCallbacks(satellitesCountResetRunnable);
            mainHandler.postDelayed(satellitesCountResetRunnable, GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS);
            updateGnssStatus();
        }
    };
    private final Runnable satellitesCountResetRunnable = () -> {
        satellitesCount = 0;
        updateGnssStatus();
    };

    private final Runnable updateDateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(this, delay);
        }
    };

    private final Runnable foregroundAppCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            mainHandler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL_MS);
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
            if (location.hasAccuracy() && location.getAccuracy() < 20.0) {
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
            mainHandler.post(() -> probeReachability());
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
                if (hasInternet) {
                    // Network claims Internet capability — do our own probe to differentiate
                    // FULL vs WHITELIST vs NONE.
                    mainHandler.post(() -> probeReachability());
                } else {
                    setWifiStatus(WiFiState.NO_INTERNET);
                }
            } else {
                setWifiStatus(WiFiState.OFF);
            }
        }
    };

    private final Runnable reachabilityProbeRunnable = new Runnable() {
        @Override
        public void run() {
            if (wifiState != WiFiState.OFF) {
                probeReachability();
            }
            mainHandler.postDelayed(this, INTERNET_PROBE_INTERVAL_MS);
        }
    };

    private ReachabilityChecker reachabilityChecker;

    private void probeReachability() {
        if (reachabilityChecker == null) {
            reachabilityChecker = new ReachabilityChecker(mainHandler);
        }
        reachabilityChecker.check(reach -> {
            if (wifiState == WiFiState.OFF) return;
            switch (reach) {
                case FULL -> setWifiStatus(WiFiState.INTERNET);
                case WHITELIST -> setWifiStatus(WiFiState.LIMITED_INTERNET);
                case NONE -> setWifiStatus(WiFiState.NO_INTERNET);
            }
        });
    }

    @Override
    public void onCreate() {
        prefs = new Preferences(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (!Permissions.allPermissionsGranted(this)) {
            prefs.widgetEnabled.set(false);
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            startMainActivity();
            stopSelf();
            return;
        }

        instance = this;

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        windowManager = getSystemService(WindowManager.class);

        createOverlayView();
    }

    private void createOverlayView() {
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        binding.getRoot().setVisibility(View.VISIBLE);
        binding.getRoot().addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            updateBackground();
            // Right-edge anchoring: when the widget content changes its measured width, shift the
            // window's left edge by the same amount so the right edge stays put. Done in a single
            // updateViewLayout to avoid the "shrink then slide" two-phase animation that
            // Gravity.RIGHT produces.
            if (params == null) return;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            if (prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(v, params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
        });

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
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = prefs.overlayX.get();
        params.y = prefs.overlayY.get();
        params.windowAnimations = 0;

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
        // Re-create date/time formatters so a locale change is reflected.
        timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        currentDateFormatPattern = null;

        if (binding != null) {
            windowManager.removeView(binding.getRoot());
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        hiddenInPackages = prefs.hideInPackages.get();
        updateForegroundAppTracking();

        updateBackground();
        updateDateTime();

        int iconSize = prefs.iconSize.get();
        int timeFontSize = prefs.timeFontSize.get();
        int dateFontSize = prefs.dateFontSize.get();
        int padding = Math.max(iconSize, Math.max(timeFontSize, dateFontSize)) / 2;

        binding.getRoot().setPadding(padding, 0, padding, 0);

        ViewGroup.LayoutParams iconParams = binding.wifiStatusIcon.getLayoutParams();
        iconParams.width = iconSize;
        iconParams.height = iconSize;
        binding.wifiStatusIcon.setLayoutParams(iconParams);

        iconParams = binding.gnssStatusIcon.getLayoutParams();
        iconParams.width = iconSize;
        iconParams.height = iconSize;
        binding.gnssStatusIcon.setLayoutParams(iconParams);

        float textOutlineWidthPx = prefs.textOutlineWidth.get();
        int outlineRgb = ContextCompat.getColor(this, R.color.text_outline) & 0x00FFFFFF;
        int textOutlineColor = outlineRgb | (prefs.textOutlineAlpha.get() << 24);
        binding.timeText.setOutlineColor(textOutlineColor);
        binding.timeText.setOutlineWidth(textOutlineWidthPx);
        binding.dateText.setOutlineColor(textOutlineColor);
        binding.dateText.setOutlineWidth(textOutlineWidthPx);

        // Icon styling (color, outline) is applied per-icon inside updateIconStatus().

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
        // Re-apply icon style for the current state (icon style may have changed in preferences).
        updateWifiStatus();
        updateGnssStatus();

        boolean hasDateOrTime = prefs.showTime.get() || prefs.showDate.get() || prefs.showDayOfTheWeek.get();
        binding.dateTimeContainer.setVisibility(hasDateOrTime ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams dateTimeLayoutParams = (LinearLayout.LayoutParams) binding.dateTimeContainer.getLayoutParams();
        dateTimeLayoutParams.setMargins(0, 0, prefs.spacingBetweenTextsAndIcons.get(), 0);
        binding.dateTimeContainer.setLayoutParams(dateTimeLayoutParams);

        binding.timeText.setTranslationY(prefs.adjustTimeY.get());
        binding.dateText.setTranslationY(prefs.adjustDateY.get());

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (prefs.showDate.get() || prefs.showTime.get() || prefs.showDayOfTheWeek.get()) {
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(updateDateTimeRunnable, delay);
        }

        if (prefs.showWifiIcon.get()) {
            if (connectivityManager == null) {
                connectivityManager = getSystemService(ConnectivityManager.class);

                // Initial state: assume "no internet" until our async probe determines whether
                // the connection is full / whitelisted / broken.
                boolean wifiPresent = false;
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(net);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        setWifiStatus(WiFiState.NO_INTERNET);
                        wifiPresent = true;
                        break;
                    }
                }

                NetworkRequest networkRequest = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback);

                if (wifiPresent) {
                    probeReachability();
                }
                mainHandler.postDelayed(reachabilityProbeRunnable, INTERNET_PROBE_INTERVAL_MS);
            }
            updateWifiStatus();
        } else if (connectivityManager != null) {
            mainHandler.removeCallbacks(reachabilityProbeRunnable);
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
            if (prefs.showGnssSatelliteBadge.get()) {
                registerSatelliteStatusReceiver();
            } else {
                unregisterSatelliteStatusReceiver();
            }
            updateGnssStatus();
        } else if (locationManager != null) {
            mainHandler.removeCallbacks(updateGnssStatusRunnable);
            unregisterSatelliteStatusReceiver();
            locationManager.removeUpdates(locationListener);
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager = null;
        }

    }

    private void registerSatelliteStatusReceiver() {
        if (satelliteReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(GNSSSHARE_SATELLITE_STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(satelliteStatusReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(satelliteStatusReceiver, filter);
        }
        satelliteReceiverRegistered = true;
    }

    private void unregisterSatelliteStatusReceiver() {
        if (!satelliteReceiverRegistered) return;
        try {
            unregisterReceiver(satelliteStatusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        satelliteReceiverRegistered = false;
        mainHandler.removeCallbacks(satellitesCountResetRunnable);
        satellitesCount = 0;
    }

    private void updateForegroundAppTracking() {
        boolean shouldTrack = !hiddenInPackages.isEmpty() && Permissions.isUsageAccessGranted(this);
        if (shouldTrack) {
            if (usageStatsManager == null) {
                usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            }
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            mainHandler.post(foregroundAppCheckRunnable);
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            applyOverlayVisibility(false);
        }
    }

    private void checkForegroundApp() {
        if (usageStatsManager == null) {
            return;
        }
        if (!Permissions.isUsageAccessGranted(this)) {
            updateForegroundAppTracking();
            return;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - FOREGROUND_APP_LOOKBACK_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latestPackage = lastForegroundPackage;
        long latestTimestamp = 0;
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latestPackage = event.getPackageName();
                }
            }
        }
        if (latestPackage == null) {
            return;
        }
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
    }

    private void applyOverlayVisibility(boolean hide) {
        if (overlayHiddenByApp == hide) {
            return;
        }
        overlayHiddenByApp = hide;
        if (binding != null) {
            binding.getRoot().setVisibility(hide ? View.GONE : View.VISIBLE);
        }
    }

    private void updateBackground() {
        if (binding == null) {
            return;
        }
        int width = binding.getRoot().getWidth();
        int height = binding.getRoot().getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int maxRadius = Math.min(width, height) / 2;
        int backgroundCornerRadius = maxRadius * prefs.backgroundCornerRadius.get() / 100;
        int backgroundColor = ContextCompat.getColor(this, R.color.widget_background) & 0x00FFFFFF | (prefs.backgroundAlpha.get() << 24);
        binding.overlayContainer.setBackground(getBackground(backgroundColor, backgroundCornerRadius));
    }

    private Drawable getBackground(int color, int cornerRadius) {
        if (this.background == null || color != this.bgColor || cornerRadius != this.bgCornerRadius) {
            this.background = new GradientDrawable();
            this.background.setColor(color);
            this.background.setCornerRadius(cornerRadius);
            this.bgColor = color;
            this.bgCornerRadius = cornerRadius;
        }

        return this.background;
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
        String dayPart = showDayOfTheWeek ? " " + dayOfTheWeekFormatStr : "";
        String datePart = showDate ? " " + dateFormatStr : "";
        String fullFormatStr = prefs.dateBeforeDayOfWeek.get()
                ? datePart + (showDate && showDayOfTheWeek ? divider : "") + dayPart + " "
                : dayPart + (showDate && showDayOfTheWeek ? divider : "") + datePart + " ";

        if (!fullFormatStr.equals(currentDateFormatPattern)) {
            dateFormat = new SimpleDateFormat(fullFormatStr, Locale.getDefault());
            currentDateFormatPattern = fullFormatStr;
        }

        Date now = new Date();
        if (showTime) {
            String timeStr = timeFormat.format(now);
            if (!timeStr.contentEquals(binding.timeText.getText())) {
                binding.timeText.setText(timeStr);
            }
        }
        if (showDate || showDayOfTheWeek) {
            String dateStr = dateFormat.format(now);
            if (!dateStr.contentEquals(binding.dateText.getText())) {
                binding.dateText.setText(dateStr);
            }
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
                    if (Math.abs(event.getRawX() - initialTouchX) < touchSlop && Math.abs(event.getRawY() - initialTouchY) < touchSlop) {
                        if (binding.wifiStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.wifiStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);

                            return true;
                        }
                        if (binding.gnssStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.gnssStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(GNSSSHARE_CLIENT_PACKAGE);
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
        if (wifiState == newState) {
            return;
        }
        wifiState = newState;
        updateWifiStatus();
    }

    private void updateWifiStatus() {
        updateIconStatus(ICON_TYPE_WIFI, binding.wifiStatusIcon, wifiState.ordinal());
    }

    private void setGnssStatus(GnssState newState) {
        if (gnssState == newState) {
            return;
        }
        gnssState = newState;
        updateGnssStatus();
    }

    private void updateGnssStatus() {
        updateIconStatus(ICON_TYPE_GNSS, binding.gnssStatusIcon, gnssState.ordinal());
    }

    private void updateIconStatus(int iconType, OutlineImageView icon, int state) {
        int designIdx = Math.min(Math.max(0, prefs.iconDesign.get()), ICON_DESIGNS.length - 1);
        int[][] design = ICON_DESIGNS[designIdx];
        int stateIdx = Math.min(Math.max(0, state), design[iconType].length - 1);
        icon.setImageResource(design[iconType][stateIdx]);
        icon.setDrawIcon(true);

        int iconStyle = Math.min(Math.max(0, prefs.iconStyle.get()), 1);
        int[] colorRes = (iconType == ICON_TYPE_WIFI) ? WIFI_STATE_COLOR_RES : GNSS_STATE_COLOR_RES;
        int tint = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(this, colorRes[stateIdx])
                : ContextCompat.getColor(this, R.color.text_primary);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));

        int outlineAlpha = prefs.iconOutlineAlpha.get();
        if (outlineAlpha > 0) {
            int haloColor = (ContextCompat.getColor(this, R.color.text_outline) & 0x00FFFFFF)
                    | (outlineAlpha << 24);
            icon.setOutlineColor(haloColor);
            icon.setOutlineWidth(prefs.iconOutlineWidth.get());
        } else {
            icon.setOutlineWidth(0);
        }

        // Whitelist (Russian-only internet) — overlay a small flag badge regardless of style.
        if (iconType == ICON_TYPE_WIFI && stateIdx == WiFiState.LIMITED_INTERNET.ordinal()) {
            Drawable flag = ContextCompat.getDrawable(this, R.drawable.ic_badge_ru_flag);
            // mutate() ensures setBounds() doesn't affect a shared cached instance.
            icon.setBadgeDrawable(flag != null ? flag.mutate() : null);
        } else {
            icon.setBadgeDrawable(null);
        }

        // GNSS Share satellite count — show as a text badge on the GPS icon.
        if (iconType == ICON_TYPE_GNSS && prefs.showGnssSatelliteBadge.get() && satellitesCount > 0
                && System.currentTimeMillis() - satellitesCountTimestamp < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
            int bgColor = (iconStyle == STYLE_COLOR)
                    ? ContextCompat.getColor(this, colorRes[stateIdx])
                    : ContextCompat.getColor(this, R.color.text_primary);
            int fgColor = ContextCompat.getColor(this, R.color.text_outline) | 0xFF000000;
            icon.setBadgeText(String.valueOf(satellitesCount), bgColor, fgColor);
        } else {
            icon.setBadgeText(null, 0, 0);
        }
    }

    // Wi-Fi state colours by ordinal (OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET).
    private static final int[] WIFI_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_error,
            R.color.status_warning,
            R.color.status_ok
    };
    // GNSS state colours by ordinal (OFF, BAD, GOOD).
    private static final int[] GNSS_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_warning,
            R.color.status_ok
    };

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

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_status_gps_good).setContentIntent(pendingIntent).setOngoing(true).build();
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
        mainHandler.removeCallbacks(foregroundAppCheckRunnable);
        mainHandler.removeCallbacks(reachabilityProbeRunnable);

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

        if (reachabilityChecker != null) {
            reachabilityChecker.shutdown();
            reachabilityChecker = null;
        }

        unregisterSatelliteStatusReceiver();
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
