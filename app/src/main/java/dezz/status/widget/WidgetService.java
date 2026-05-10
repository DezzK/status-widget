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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
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

    private Context themedContext;
    private int appliedThemePref = -1;

    private MediaSessionManager mediaSessionManager;
    private final List<MediaController> activeMediaControllers = new ArrayList<>();
    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            updateMediaInfo();
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            updateMediaInfo();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            this::rebindMediaControllers;

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
        // If the user is in "follow system" mode, the system uiMode flip means the cached
        // themedContext now points at the wrong configuration — invalidate so the next
        // applyPreferences() rebuilds it.
        themedContext = null;
        appliedThemePref = -1;

        if (binding != null) {
            windowManager.removeView(binding.getRoot());
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        hiddenInPackages = prefs.hideInPackages.get();
        rebuildEffectiveHideLists();
        updateForegroundAppTracking();
        updateThemedContext();

        updateBackground();
        updateDateTime();

        List<BrickType> bricks = BrickType.parseOrder(prefs.brickOrder.get());
        Set<BrickType> bricksSet = EnumSet.noneOf(BrickType.class);
        bricksSet.addAll(bricks);

        // Reorder children of the root LinearLayout to match brickOrder. Hidden bricks are
        // appended at the end with View.GONE — kept attached so we don't need to re-bind state.
        reorderBricks(bricks);

        // Apply each brick's settings (size/font, outline, margins) — independent of visibility.
        applyTimeBrickSettings();
        applyDateBrickSettings();
        applyMediaBrickSettings();
        applyWifiBrickSettings();
        applyGpsBrickSettings();

        applyBrickVisibility(bricksSet);

        // Re-apply icon style for the current state — icon style and outline may have changed.
        updateWifiStatus();
        updateGnssStatus();

        // Root horizontal padding is the largest brick dimension among the visible bricks. We
        // need it because text outlines and icon outlines bleed past the view bounds and would
        // otherwise be clipped by the window surface.
        int padding = 0;
        if (bricksSet.contains(BrickType.TIME)) padding = Math.max(padding, prefs.time.fontSize.get());
        if (bricksSet.contains(BrickType.DATE)) padding = Math.max(padding, prefs.date.fontSize.get());
        if (bricksSet.contains(BrickType.MEDIA)) padding = Math.max(padding, prefs.media.fontSize.get());
        if (bricksSet.contains(BrickType.WIFI)) padding = Math.max(padding, prefs.wifi.size.get());
        if (bricksSet.contains(BrickType.GPS)) padding = Math.max(padding, prefs.gps.size.get());
        binding.getRoot().setPadding(padding / 2, 0, padding / 2, 0);

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (bricksSet.contains(BrickType.TIME) || bricksSet.contains(BrickType.DATE)) {
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(updateDateTimeRunnable, delay);
        }

        if (bricksSet.contains(BrickType.WIFI)) {
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

        if (bricksSet.contains(BrickType.GPS)) {
            if (locationManager == null) {
                locationManager = getSystemService(LocationManager.class);

                locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
                mainHandler.postDelayed(updateGnssStatusRunnable, GNSS_STATUS_CHECK_INTERVAL);
            }
            if (prefs.gps.showSatelliteBadge.get()) {
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

        if (bricksSet.contains(BrickType.MEDIA) && Permissions.isNotificationAccessGranted(this)) {
            enableMediaTracking();
        } else {
            disableMediaTracking();
            binding.mediaContainer.setVisibility(View.GONE);
        }
    }

    private void reorderBricks(List<BrickType> bricks) {
        LinearLayout root = (LinearLayout) binding.getRoot();
        List<View> expected = new ArrayList<>();
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v != null) expected.add(v);
        }
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) expected.add(v);
            }
        }
        boolean inOrder = root.getChildCount() == expected.size();
        if (inOrder) {
            for (int i = 0; i < expected.size(); i++) {
                if (root.getChildAt(i) != expected.get(i)) {
                    inOrder = false;
                    break;
                }
            }
        }
        if (!inOrder) {
            root.removeAllViews();
            for (View v : expected) {
                root.addView(v);
            }
        }
    }

    @Nullable
    private View viewForBrick(BrickType type) {
        switch (type) {
            case TIME:
                return binding.timeText;
            case DATE:
                return binding.dateText;
            case MEDIA:
                return binding.mediaContainer;
            case WIFI:
                return binding.wifiStatusIcon;
            case GPS:
                return binding.gnssStatusIcon;
            default:
                return null;
        }
    }

    private void applyTimeBrickSettings() {
        applySingleLineTextBrick(binding.timeText, prefs.time);
    }

    private void applyDateBrickSettings() {
        applySingleLineTextBrick(binding.dateText, prefs.date);
        switch (prefs.date.alignment.get()) {
            case 1:
                binding.dateText.setGravity(Gravity.CENTER_HORIZONTAL);
                break;
            case 2:
                binding.dateText.setGravity(Gravity.END);
                break;
            default:
                binding.dateText.setGravity(Gravity.START);
                break;
        }
    }

    private void applyMediaBrickSettings() {
        int outlineColor = textOutlineColor(prefs.media.outlineAlpha.get());
        int textColor = ContextCompat.getColor(themedContext, R.color.text_primary);
        binding.mediaAppText.setOutlineColor(outlineColor);
        binding.mediaAppText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaAppText.setTextColor(textColor);
        binding.mediaTitleText.setOutlineColor(outlineColor);
        binding.mediaTitleText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaTitleText.setTextColor(textColor);
        binding.mediaAppText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        binding.mediaTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        applyHorizontalMargins(binding.mediaContainer, prefs.media.marginStart.get(), prefs.media.marginEnd.get());
    }

    private void applyWifiBrickSettings() {
        ViewGroup.LayoutParams ip = binding.wifiStatusIcon.getLayoutParams();
        ip.width = prefs.wifi.size.get();
        ip.height = prefs.wifi.size.get();
        binding.wifiStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.wifiStatusIcon, prefs.wifi.marginStart.get(), prefs.wifi.marginEnd.get());
    }

    private void applyGpsBrickSettings() {
        ViewGroup.LayoutParams ip = binding.gnssStatusIcon.getLayoutParams();
        ip.width = prefs.gps.size.get();
        ip.height = prefs.gps.size.get();
        binding.gnssStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.gnssStatusIcon, prefs.gps.marginStart.get(), prefs.gps.marginEnd.get());
    }

    private void applySingleLineTextBrick(OutlineTextView view, Preferences.SingleLineTextBrickPrefs p) {
        view.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        view.setOutlineColor(textOutlineColor(p.outlineAlpha.get()));
        view.setOutlineWidth(p.outlineWidth.get());
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.fontSize.get());
        view.setTranslationY(p.adjustY.get());
        applyHorizontalMargins(view, p.marginStart.get(), p.marginEnd.get());
    }

    private int textOutlineColor(int alpha) {
        return (ContextCompat.getColor(themedContext, R.color.text_outline) & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Rebuilds {@link #themedContext} so theme-dependent colour lookups respect the user's
     * "Widget theme" preference. Pref values: 0 = follow system, 1 = always light, 2 = always
     * dark. Cached so we don't allocate a new Context on every {@code applyPreferences()}.
     */
    private void updateThemedContext() {
        int pref = prefs.widgetTheme.get();
        if (themedContext != null && pref == appliedThemePref) return;
        if (pref == 0) {
            themedContext = this;
        } else {
            Configuration cfg = new Configuration(getResources().getConfiguration());
            int uiMode = (pref == 1)
                    ? Configuration.UI_MODE_NIGHT_NO
                    : Configuration.UI_MODE_NIGHT_YES;
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | uiMode;
            themedContext = createConfigurationContext(cfg);
        }
        appliedThemePref = pref;
    }

    private static void applyHorizontalMargins(View view, int start, int end) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        view.setLayoutParams(lp);
    }

    private final EnumMap<BrickType, Set<String>> effectiveHideLists = new EnumMap<>(BrickType.class);

    private void rebuildEffectiveHideLists() {
        effectiveHideLists.clear();
        for (BrickType type : BrickType.values()) {
            BrickType source = prefs.effectiveHideSourceFor(type);
            effectiveHideLists.put(type, prefs.hideListFor(source).get());
        }
    }

    private boolean isBrickHiddenByApp(BrickType type) {
        if (lastForegroundPackage == null) return false;
        Set<String> list = effectiveHideLists.get(type);
        return list != null && list.contains(lastForegroundPackage);
    }

    private boolean anyBrickHasHideList() {
        for (Set<String> s : effectiveHideLists.values()) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    private void applyBrickVisibility(Set<BrickType> bricksSet) {
        if (binding == null) return;
        binding.timeText.setVisibility(
                (bricksSet.contains(BrickType.TIME) && !isBrickHiddenByApp(BrickType.TIME))
                        ? View.VISIBLE : View.GONE);
        boolean dateActive = bricksSet.contains(BrickType.DATE)
                && (prefs.date.showDate.get() || prefs.date.showDayOfWeek.get())
                && !isBrickHiddenByApp(BrickType.DATE);
        binding.dateText.setVisibility(dateActive ? View.VISIBLE : View.GONE);
        binding.wifiStatusIcon.setVisibility(
                (bricksSet.contains(BrickType.WIFI) && !isBrickHiddenByApp(BrickType.WIFI))
                        ? View.VISIBLE : View.GONE);
        binding.gnssStatusIcon.setVisibility(
                (bricksSet.contains(BrickType.GPS) && !isBrickHiddenByApp(BrickType.GPS))
                        ? View.VISIBLE : View.GONE);
        // Media visibility is also gated by the active media session — see updateMediaInfo().
        if (!bricksSet.contains(BrickType.MEDIA) || isBrickHiddenByApp(BrickType.MEDIA)) {
            binding.mediaContainer.setVisibility(View.GONE);
        } else {
            updateMediaInfo();
        }
    }

    private Set<BrickType> currentBrickSet() {
        Set<BrickType> set = EnumSet.noneOf(BrickType.class);
        set.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        return set;
    }

    private void enableMediaTracking() {
        if (mediaSessionManager != null) return;
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) return;
        ComponentName component = new ComponentName(this, MediaNotificationListener.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, mainHandler);
            rebindMediaControllers(mediaSessionManager.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not granted; media tracking disabled", e);
            mediaSessionManager = null;
        }
    }

    private void disableMediaTracking() {
        if (mediaSessionManager == null) return;
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
        } catch (Exception ignored) {
        }
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        mediaSessionManager = null;
    }

    private void rebindMediaControllers(@Nullable List<MediaController> controllers) {
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                activeMediaControllers.add(c);
                c.registerCallback(mediaControllerCallback, mainHandler);
            }
        }
        updateMediaInfo();
    }

    private void updateMediaInfo() {
        if (binding == null) return;
        if (!currentBrickSet().contains(BrickType.MEDIA) || isBrickHiddenByApp(BrickType.MEDIA)) {
            binding.mediaContainer.setVisibility(View.GONE);
            return;
        }
        MediaController playing = pickActiveMediaController();
        if (playing == null) {
            binding.mediaContainer.setVisibility(View.GONE);
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        String title = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        String artist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        String subtitle;
        if (!isEmpty(artist) && !isEmpty(title)) {
            subtitle = artist + " — " + title;
        } else if (!isEmpty(title)) {
            subtitle = title;
        } else if (!isEmpty(artist)) {
            subtitle = artist;
        } else {
            subtitle = "";
        }
        if (subtitle.isEmpty()) {
            binding.mediaContainer.setVisibility(View.GONE);
            return;
        }
        binding.mediaAppText.setText(getAppLabel(playing.getPackageName()));
        binding.mediaTitleText.setText(subtitle);
        binding.mediaContainer.setVisibility(View.VISIBLE);
    }

    @Nullable
    private MediaController pickActiveMediaController() {
        for (MediaController c : activeMediaControllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                return c;
            }
        }
        return null;
    }

    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
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
        boolean shouldTrack = (!hiddenInPackages.isEmpty() || anyBrickHasHideList())
                && Permissions.isUsageAccessGranted(this);
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
        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
        if (changed && binding != null) {
            applyBrickVisibility(currentBrickSet());
        }
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
        if (themedContext == null) {
            updateThemedContext();
        }
        int width = binding.getRoot().getWidth();
        int height = binding.getRoot().getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int maxRadius = Math.min(width, height) / 2;
        int backgroundCornerRadius = maxRadius * prefs.backgroundCornerRadius.get() / 100;
        int backgroundColor = ContextCompat.getColor(themedContext, R.color.widget_background) & 0x00FFFFFF | (prefs.backgroundAlpha.get() << 24);
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
        Set<BrickType> bricks = EnumSet.noneOf(BrickType.class);
        bricks.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        boolean showTime = bricks.contains(BrickType.TIME);
        boolean dateBrickActive = bricks.contains(BrickType.DATE);
        boolean showDate = dateBrickActive && prefs.date.showDate.get();
        boolean showDayOfTheWeek = dateBrickActive && prefs.date.showDayOfWeek.get();

        if (!showTime && !showDate && !showDayOfTheWeek) {
            return;
        }

        boolean showFullDayAndMonth = prefs.date.showFullName.get();

        String divider = (showDate && showDayOfTheWeek) ? (prefs.date.oneLineLayout.get() ? "," : " \n") : "";
        String dayOfTheWeekFormatStr = showFullDayAndMonth ? "EEEE" : "EEE";
        String dateFormatStr = showFullDayAndMonth ? "d MMMM" : "d MMM";

        // We add spaces at the start/end to avoid outline cropping by canvas which is not ready for the outline
        String dayPart = showDayOfTheWeek ? " " + dayOfTheWeekFormatStr : "";
        String datePart = showDate ? " " + dateFormatStr : "";
        String fullFormatStr = prefs.date.dateBeforeDayOfWeek.get()
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
                ? ContextCompat.getColor(themedContext, colorRes[stateIdx])
                : ContextCompat.getColor(themedContext, R.color.text_primary);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));

        Preferences.IconBrickPrefs iconPrefs = (iconType == ICON_TYPE_WIFI) ? prefs.wifi : prefs.gps;
        int outlineAlpha = iconPrefs.outlineAlpha.get();
        if (outlineAlpha > 0) {
            int haloColor = (ContextCompat.getColor(themedContext, R.color.text_outline) & 0x00FFFFFF)
                    | (outlineAlpha << 24);
            icon.setOutlineColor(haloColor);
            icon.setOutlineWidth(iconPrefs.outlineWidth.get());
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
        if (iconType == ICON_TYPE_GNSS && prefs.gps.showSatelliteBadge.get() && satellitesCount > 0
                && System.currentTimeMillis() - satellitesCountTimestamp < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
            int bgColor = (iconStyle == STYLE_COLOR)
                    ? ContextCompat.getColor(themedContext, colorRes[stateIdx])
                    : ContextCompat.getColor(themedContext, R.color.text_primary);
            int fgColor = ContextCompat.getColor(themedContext, R.color.text_outline) | 0xFF000000;
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
        disableMediaTracking();
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
