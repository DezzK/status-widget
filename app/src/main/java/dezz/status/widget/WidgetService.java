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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import java.util.HashSet;
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

    enum BluetoothState {
        OFF, NO_DEVICE, CONNECTED
    }

    // Icon designs: 4 Wi-Fi states, 3 GNSS states, 3 Bluetooth states.
    private static final int[][] DESIGN_CLASSIC = {
            {
                    R.drawable.ic_status_wifi_off,
                    R.drawable.ic_status_wifi_no_internet,
                    R.drawable.ic_status_wifi_whitelist,
                    R.drawable.ic_status_wifi_internet
            },
            { R.drawable.ic_status_gps_off, R.drawable.ic_status_gps_bad, R.drawable.ic_status_gps_good },
            { R.drawable.ic_status_bt_off, R.drawable.ic_status_bt_no_device, R.drawable.ic_status_bt_connected }
    };
    private static final int[][] DESIGN_SOLID = {
            {
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            { R.drawable.ic_status_filled_gps_off, R.drawable.ic_status_filled_gps_bad, R.drawable.ic_status_filled_gps_good },
            { R.drawable.ic_status_filled_bt_off, R.drawable.ic_status_filled_bt_no_device, R.drawable.ic_status_filled_bt_connected }
    };
    private static final int[][] DESIGN_BARS = {
            {
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            },
            { R.drawable.ic_status_bars_gps_off, R.drawable.ic_status_bars_gps_bad, R.drawable.ic_status_bars_gps_good },
            { R.drawable.ic_status_bars_bt_off, R.drawable.ic_status_bars_bt_no_device, R.drawable.ic_status_bars_bt_connected }
    };
    private static final int[][][] ICON_DESIGNS = { DESIGN_CLASSIC, DESIGN_SOLID, DESIGN_BARS };

    private static final int ICON_TYPE_WIFI = 0;
    private static final int ICON_TYPE_GNSS = 1;
    private static final int ICON_TYPE_BT = 2;

    private static final int WIDGET_MODE_FLOATING = 0;
    private static final int WIDGET_MODE_STATUS_BAR = 1;

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
    private BluetoothState bluetoothState = BluetoothState.OFF;
    private final Set<String> btConnectedAddrs = new HashSet<>();
    private boolean btReceiverRegistered = false;

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

    /** Fires when the overlay's position or size changes so the settings UI can stay in sync. */
    public interface OverlayStateListener {
        void onOverlayStateChanged(int x, int y, int width, int height);
    }

    @Nullable private OverlayStateListener overlayStateListener;

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

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    btConnectedAddrs.clear();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    refreshBtConnectedFromProxies();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    btConnectedAddrs.add(device.getAddress());
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    btConnectedAddrs.remove(device.getAddress());
                }
            }
            updateBluetoothStatus();
        }
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
            if (prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR
                    && prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(v, params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
            notifyOverlayState();
        });

        applyPreferences();

        updateWifiStatus();
        updateGnssStatus();

        // Set up drag listener
        setupDragListener();

        // Add the view to the window
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        params = new WindowManager.LayoutParams(
                statusBar
                        ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                ,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = statusBar ? 0 : prefs.overlayX.get();
        params.y = statusBar ? 0 : prefs.overlayY.get();
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
        applyBluetoothBrickSettings();

        applyBrickVisibility(bricksSet);
        applyOverlayPosition();

        // Re-apply icon style for the current state — icon style and outline may have changed.
        updateWifiStatus();
        updateGnssStatus();
        updateBluetoothStatus();

        // User-controllable global padding around the widget content (four independent sides).
        // Was previously auto-computed as half of the largest brick dimension — many users found
        // it too wide on small head units, so it's now explicit prefs. Slight outline clipping
        // at thin paddings is acceptable.
        binding.getRoot().setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());

        // Lock the widget height to the tallest brick that's in the user's chosen order —
        // including bricks currently hidden per-app. Otherwise hiding e.g. a big Time brick
        // would let the row shrink vertically and the remaining icons would re-center up,
        // breaking alignment with the device status bar that users carefully tune.
        binding.getRoot().setMinimumHeight(computeMinWidgetHeight(bricksSet));

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

        if (bricksSet.contains(BrickType.BLUETOOTH)) {
            registerBluetoothReceiver();
            refreshBtConnectedFromProxies();
        } else {
            unregisterBluetoothReceiver();
            btConnectedAddrs.clear();
        }
        updateBluetoothStatus();

        if (bricksSet.contains(BrickType.MEDIA) && Permissions.isNotificationAccessGranted(this)) {
            enableMediaTracking();
        } else {
            disableMediaTracking();
            binding.mediaContainer.setVisibility(View.GONE);
        }
    }

    private void reorderBricks(List<BrickType> bricks) {
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            reorderForStatusBar(bricks);
        } else {
            reorderForFloating(bricks);
        }
    }

    private void reorderForFloating(List<BrickType> bricks) {
        LinearLayout root = (LinearLayout) binding.getRoot();
        // Status-bar group containers and spacers are hidden in floating mode and emptied so
        // bricks live as direct children of the root again.
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();
        binding.startGroup.setVisibility(View.GONE);
        binding.centerGroup.setVisibility(View.GONE);
        binding.endGroup.setVisibility(View.GONE);
        binding.startCenterSpacer.setVisibility(View.GONE);
        binding.centerEndSpacer.setVisibility(View.GONE);

        List<View> expected = new ArrayList<>();
        // Re-include the (empty) groups + spacers so their visibility=GONE keeps them out of
        // measure but the views remain attached to the same root for next switch.
        expected.add(binding.startGroup);
        expected.add(binding.startCenterSpacer);
        expected.add(binding.centerGroup);
        expected.add(binding.centerEndSpacer);
        expected.add(binding.endGroup);
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
        applyChildOrder(root, expected);
    }

    private void reorderForStatusBar(List<BrickType> bricks) {
        LinearLayout root = (LinearLayout) binding.getRoot();
        // Detach bricks from wherever they currently sit (root or any group).
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();

        // Root order: startGroup, spacer, centerGroup, spacer, endGroup. Hidden bricks dangle off
        // the root after these so they remain attached but invisible.
        List<View> rootChildren = new ArrayList<>();
        rootChildren.add(binding.startGroup);
        rootChildren.add(binding.startCenterSpacer);
        rootChildren.add(binding.centerGroup);
        rootChildren.add(binding.centerEndSpacer);
        rootChildren.add(binding.endGroup);
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) rootChildren.add(v);
            }
        }
        applyChildOrder(root, rootChildren);

        // Distribute visible bricks into the proper alignment group.
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v == null) continue;
            int alignment = clampAlignment(prefs.statusAlignmentFor(type).get());
            LinearLayout target = (alignment == 1) ? binding.centerGroup
                    : (alignment == 2) ? binding.endGroup
                    : binding.startGroup;
            target.addView(v);
        }

        binding.startGroup.setVisibility(View.VISIBLE);
        binding.centerGroup.setVisibility(View.VISIBLE);
        binding.endGroup.setVisibility(View.VISIBLE);
        binding.startCenterSpacer.setVisibility(View.VISIBLE);
        binding.centerEndSpacer.setVisibility(View.VISIBLE);
    }

    private static void applyChildOrder(ViewGroup parent, List<View> expected) {
        boolean inOrder = parent.getChildCount() == expected.size();
        if (inOrder) {
            for (int i = 0; i < expected.size(); i++) {
                if (parent.getChildAt(i) != expected.get(i)) {
                    inOrder = false;
                    break;
                }
            }
        }
        if (inOrder) return;
        parent.removeAllViews();
        for (View v : expected) {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
            parent.addView(v);
        }
    }

    private static int clampAlignment(int v) {
        return v < 0 ? 0 : (v > 2 ? 2 : v);
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
            case BLUETOOTH:
                return binding.bluetoothStatusIcon;
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
        Typeface typeface = Fonts.resolve(this, prefs.media.fontFamily.get(),
                prefs.media.fontBold.get(), prefs.media.fontItalic.get());
        binding.mediaAppText.setOutlineColor(outlineColor);
        binding.mediaAppText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaAppText.setTextColor(textColor);
        binding.mediaAppText.setTypeface(typeface);
        binding.mediaTitleText.setOutlineColor(outlineColor);
        binding.mediaTitleText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaTitleText.setTextColor(textColor);
        binding.mediaTitleText.setTypeface(typeface);
        binding.mediaAppText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        binding.mediaTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        applyHorizontalMargins(binding.mediaContainer, prefs.media.marginStart.get(), prefs.media.marginEnd.get());
        binding.mediaContainer.setTranslationY(prefs.media.adjustY.get());
        applyMediaMaxWidth(binding.mediaAppText);
        applyMediaMaxWidth(binding.mediaTitleText);
        applyMediaChildAlignment(binding.mediaAppText, prefs.media.alignment.get());
        applyMediaChildAlignment(binding.mediaTitleText, prefs.media.alignment.get());
    }

    /**
     * Horizontal alignment of a single text line within the vertical media container.
     * Container is wrap_content (sized to the wider of the two children), so the narrower
     * child shifts within that band via its own {@code layout_gravity}.
     */
    private static void applyMediaChildAlignment(View view, int alignment) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        int gravity;
        switch (alignment) {
            case 1: gravity = Gravity.CENTER_HORIZONTAL; break;
            case 2: gravity = Gravity.END; break;
            default: gravity = Gravity.START; break;
        }
        lp.gravity = gravity;
        view.setLayoutParams(lp);
    }

    private void applyMediaMaxWidth(OutlineTextView view) {
        int maxWidth = prefs.media.maxWidth.get();
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        // Wrap to content but never exceed the user-chosen maximum — short texts stay short,
        // long ones cap at maxWidth and switch to marquee scrolling.
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        view.setLayoutParams(lp);
        view.setMaxWidth(maxWidth);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1); // marquee_forever
        // Marquee only animates while the view "is selected"; force it on so a static overlay
        // (which never receives input focus) still scrolls long titles.
        view.setSelected(true);
    }

    private void applyWifiBrickSettings() {
        ViewGroup.LayoutParams ip = binding.wifiStatusIcon.getLayoutParams();
        ip.width = prefs.wifi.size.get();
        ip.height = prefs.wifi.size.get();
        binding.wifiStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.wifiStatusIcon, prefs.wifi.marginStart.get(), prefs.wifi.marginEnd.get());
        binding.wifiStatusIcon.setTranslationY(prefs.wifi.adjustY.get());
    }

    private void applyGpsBrickSettings() {
        ViewGroup.LayoutParams ip = binding.gnssStatusIcon.getLayoutParams();
        ip.width = prefs.gps.size.get();
        ip.height = prefs.gps.size.get();
        binding.gnssStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.gnssStatusIcon, prefs.gps.marginStart.get(), prefs.gps.marginEnd.get());
        binding.gnssStatusIcon.setTranslationY(prefs.gps.adjustY.get());
    }

    private void applyBluetoothBrickSettings() {
        ViewGroup.LayoutParams ip = binding.bluetoothStatusIcon.getLayoutParams();
        ip.width = prefs.bluetooth.size.get();
        ip.height = prefs.bluetooth.size.get();
        binding.bluetoothStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.bluetoothStatusIcon,
                prefs.bluetooth.marginStart.get(), prefs.bluetooth.marginEnd.get());
        binding.bluetoothStatusIcon.setTranslationY(prefs.bluetooth.adjustY.get());
    }

    private void applySingleLineTextBrick(OutlineTextView view, Preferences.TextBrickPrefs p) {
        view.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        view.setOutlineColor(textOutlineColor(p.outlineAlpha.get()));
        view.setOutlineWidth(p.outlineWidth.get());
        view.setTypeface(Fonts.resolve(this, p.fontFamily.get(), p.fontBold.get(), p.fontItalic.get()));
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
     * dark, 3 = inverse of system. Cached so we don't allocate a new Context on every
     * {@code applyPreferences()}; {@code onConfigurationChanged} invalidates the cache so the
     * inverse mode picks up system theme changes too.
     */
    private void updateThemedContext() {
        int pref = prefs.widgetTheme.get();
        if (themedContext != null && pref == appliedThemePref) return;
        if (pref == 0) {
            themedContext = this;
        } else {
            int uiMode;
            if (pref == 1) {
                uiMode = Configuration.UI_MODE_NIGHT_NO;
            } else if (pref == 2) {
                uiMode = Configuration.UI_MODE_NIGHT_YES;
            } else {
                int systemNight = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                uiMode = (systemNight == Configuration.UI_MODE_NIGHT_YES)
                        ? Configuration.UI_MODE_NIGHT_NO
                        : Configuration.UI_MODE_NIGHT_YES;
            }
            Configuration cfg = new Configuration(getResources().getConfiguration());
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
        binding.bluetoothStatusIcon.setVisibility(
                (bricksSet.contains(BrickType.BLUETOOTH) && !isBrickHiddenByApp(BrickType.BLUETOOTH))
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

    /**
     * Computes the tallest brick height (in pixels) over all bricks currently in
     * {@code brickOrder}, regardless of per-app visibility. Used as the widget's minimum height so
     * a brick disappearing on a particular app doesn't shrink the row.
     *
     * Text bricks use {@link Paint#getFontMetrics()} on a copy of the TextView's paint at the
     * given pixel size — this matches exactly the height the TextView itself would measure for a
     * single line (with {@code includeFontPadding=true}, the default).
     */
    private int computeMinWidgetHeight(Set<BrickType> bricks) {
        int h = 0;
        if (bricks.contains(BrickType.TIME)) {
            h = Math.max(h, textLineHeight(binding.timeText, prefs.time.fontSize.get()));
        }
        if (bricks.contains(BrickType.DATE)) {
            // Two lines when day-of-week + date are both shown and not collapsed into one line.
            int lines = (prefs.date.showDate.get() && prefs.date.showDayOfWeek.get()
                    && !prefs.date.oneLineLayout.get()) ? 2 : 1;
            h = Math.max(h, textLineHeight(binding.dateText, prefs.date.fontSize.get()) * lines);
        }
        if (bricks.contains(BrickType.MEDIA)) {
            // Media is one or two stacked lines depending on showSource (app name + title).
            int mediaLines = prefs.media.showSource.get() ? 2 : 1;
            h = Math.max(h, textLineHeight(binding.mediaAppText, prefs.media.fontSize.get()) * mediaLines);
        }
        if (bricks.contains(BrickType.WIFI)) {
            h = Math.max(h, prefs.wifi.size.get());
        }
        if (bricks.contains(BrickType.GPS)) {
            h = Math.max(h, prefs.gps.size.get());
        }
        if (bricks.contains(BrickType.BLUETOOTH)) {
            h = Math.max(h, prefs.bluetooth.size.get());
        }
        return h;
    }

    private static int textLineHeight(OutlineTextView view, int fontSizePx) {
        // Copy so we don't mutate the live drawing paint. The copy preserves typeface, which is
        // crucial because Roboto Condensed Medium has different metrics from the default.
        Paint p = new Paint(view.getPaint());
        p.setTextSize(fontSizePx);
        Paint.FontMetrics fm = p.getFontMetrics();
        // TextView with includeFontPadding=true (default) uses top/bottom for the layout bounds.
        return (int) Math.ceil(fm.bottom - fm.top);
    }

    public void setOverlayStateListener(@Nullable OverlayStateListener listener) {
        this.overlayStateListener = listener;
        if (listener != null) {
            notifyOverlayState();
        }
    }

    private void notifyOverlayState() {
        if (overlayStateListener == null || params == null || binding == null) return;
        overlayStateListener.onOverlayStateChanged(
                params.x, params.y,
                binding.getRoot().getWidth(),
                binding.getRoot().getHeight());
    }

    /**
     * Pushes the saved widget position and mode-specific window params into the WindowManager.
     * Called from {@link #applyPreferences()} so the position sliders / mode switcher in
     * settings affect the widget live. Skipped when the widget isn't drawn yet.
     */
    private void applyOverlayPosition() {
        if (params == null || binding == null || windowManager == null) return;
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        int newWidth = statusBar
                ? WindowManager.LayoutParams.MATCH_PARENT
                : WindowManager.LayoutParams.WRAP_CONTENT;
        int newX = statusBar ? 0 : prefs.overlayX.get();
        int newY = statusBar ? 0 : prefs.overlayY.get();
        if (params.x == newX && params.y == newY && params.width == newWidth) return;
        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {
        }
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
        String title = pickMediaTitle(metadata);
        String artist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (isUnknownArtistPlaceholder(artist)) {
            // Some players (notably stock Android Music) fill the artist field with a literal
            // "Unknown artist" / "Неизвестный исполнитель" string when the tag is missing.
            // Treat that as no artist so the subtitle falls back to the title alone.
            artist = null;
        }
        String subtitle;
        if (!isEmpty(artist) && !isEmpty(title)) {
            subtitle = artist + " — " + title;
        } else if (!isEmpty(title)) {
            subtitle = title;
        } else if (!isEmpty(artist)) {
            subtitle = artist;
        } else {
            // Something is playing but the player exposes no metadata at all — at least show a
            // placeholder so the user can see that media playback is active.
            subtitle = getString(R.string.media_unknown_track);
        }
        binding.mediaAppText.setText(getAppLabel(playing.getPackageName()));
        binding.mediaAppText.setVisibility(prefs.media.showSource.get() ? View.VISIBLE : View.GONE);
        binding.mediaTitleText.setText(subtitle);
        binding.mediaContainer.setVisibility(View.VISIBLE);
    }

    /**
     * Best-effort extraction of a track title from the media metadata. Falls back through several
     * standard keys, then to the file name parsed out of the media URI, so we still show something
     * useful for players that don't populate {@link MediaMetadata#METADATA_KEY_TITLE}.
     */
    @Nullable
    private static String pickMediaTitle(@Nullable MediaMetadata metadata) {
        if (metadata == null) return null;
        String[] keys = {
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        };
        for (String key : keys) {
            String value = metadata.getString(key);
            if (!isEmpty(value)) return value;
        }
        String uriFilename = filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI));
        if (!isEmpty(uriFilename)) return uriFilename;
        return filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    /**
     * Recognise the literal "Unknown artist" / "Неизвестный исполнитель" placeholders that
     * some players write into the artist field when the tag is missing — case-insensitive
     * and whitespace-tolerant.
     */
    private static boolean isUnknownArtistPlaceholder(@Nullable String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        return trimmed.equalsIgnoreCase("unknown artist")
                || trimmed.equalsIgnoreCase("неизвестный исполнитель");
    }

    @Nullable
    private static String filenameFromUri(@Nullable String raw) {
        if (isEmpty(raw)) return null;
        String last = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(raw);
            last = uri.getLastPathSegment();
        } catch (Exception ignored) {
        }
        if (isEmpty(last)) {
            int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
            last = (slash >= 0 && slash < raw.length() - 1) ? raw.substring(slash + 1) : raw;
        }
        if (isEmpty(last)) return null;
        int dot = last.lastIndexOf('.');
        if (dot > 0) {
            last = last.substring(0, dot);
        }
        return android.net.Uri.decode(last);
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

    private void registerBluetoothReceiver() {
        if (btReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            registerReceiver(bluetoothReceiver, filter);
            btReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register Bluetooth receiver", t);
        }
    }

    private void unregisterBluetoothReceiver() {
        if (!btReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        btReceiverRegistered = false;
    }

    @Nullable
    private static BluetoothAdapter getBluetoothAdapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Seed the connected-device set from profile proxies. ACL_CONNECTED broadcasts only fire on
     * link change — devices that are already connected when the receiver registers wouldn't show
     * up otherwise. Query HEADSET + A2DP (the common car-HU profiles) asynchronously and union
     * their {@code getConnectedDevices()} lists into {@link #btConnectedAddrs}.
     */
    private void refreshBtConnectedFromProxies() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) return;
        try {
            if (!adapter.isEnabled()) {
                btConnectedAddrs.clear();
                return;
            }
        } catch (Throwable t) {
            return;
        }
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    for (BluetoothDevice d : proxy.getConnectedDevices()) {
                        if (d != null && d.getAddress() != null) {
                            btConnectedAddrs.add(d.getAddress());
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Throwable ignored) {
                }
                updateBluetoothStatus();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };
        try {
            adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
            adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query Bluetooth profile proxies", t);
        }
    }

    private void updateBluetoothStatus() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        boolean enabled;
        try {
            enabled = adapter != null && adapter.isEnabled();
        } catch (Throwable t) {
            enabled = false;
        }
        BluetoothState newState;
        if (!enabled) {
            newState = BluetoothState.OFF;
            btConnectedAddrs.clear();
        } else if (btConnectedAddrs.isEmpty()) {
            newState = BluetoothState.NO_DEVICE;
        } else {
            newState = BluetoothState.CONNECTED;
        }
        bluetoothState = newState;
        if (binding != null) {
            updateIconStatus(ICON_TYPE_BT, binding.bluetoothStatusIcon, bluetoothState.ordinal());
        }
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
        int backgroundCornerRadius = (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR)
                ? 0
                : maxRadius * prefs.backgroundCornerRadius.get() / 100;
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
                    if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
                        // Pinned to (0, 0) full-width — drag is disabled, but consume the event so
                        // ACTION_UP still arrives for click handling.
                        return true;
                    }
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(binding.getRoot(), params);
                    notifyOverlayState();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                        savePosition();
                    }

                    // Handle click
                    if (Math.abs(event.getRawX() - initialTouchX) < touchSlop && Math.abs(event.getRawY() - initialTouchY) < touchSlop) {
                        if (binding.wifiStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.wifiStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }
                        if (binding.gnssStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.gnssStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(GNSSSHARE_CLIENT_PACKAGE);
                            if (intent == null) {
                                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
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
        safeStartActivity(startIntent);
    }

    /**
     * Some car head units don't ship the system Wi-Fi / location / app-info activities at all,
     * so launching them from the overlay throws ActivityNotFoundException and tears down the
     * service process. Swallow the failure — the icon tap is non-essential.
     */
    private void safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed for " + intent.getAction(), t);
        }
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
        int[] colorRes;
        Preferences.IconBrickPrefs iconPrefs;
        switch (iconType) {
            case ICON_TYPE_GNSS:
                colorRes = GNSS_STATE_COLOR_RES;
                iconPrefs = prefs.gps;
                break;
            case ICON_TYPE_BT:
                colorRes = BT_STATE_COLOR_RES;
                iconPrefs = prefs.bluetooth;
                break;
            case ICON_TYPE_WIFI:
            default:
                colorRes = WIFI_STATE_COLOR_RES;
                iconPrefs = prefs.wifi;
                break;
        }
        int tint = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(themedContext, colorRes[stateIdx])
                : ContextCompat.getColor(themedContext, R.color.text_primary);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));

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

        // Text badge: GNSS Share satellite count for GPS, connected-device count for Bluetooth.
        String badgeText = null;
        if (iconType == ICON_TYPE_GNSS && prefs.gps.showSatelliteBadge.get() && satellitesCount > 0
                && System.currentTimeMillis() - satellitesCountTimestamp < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
            badgeText = String.valueOf(satellitesCount);
        } else if (iconType == ICON_TYPE_BT && prefs.bluetooth.showDeviceCountBadge.get()
                && bluetoothState == BluetoothState.CONNECTED && !btConnectedAddrs.isEmpty()) {
            badgeText = String.valueOf(btConnectedAddrs.size());
        }
        if (badgeText != null) {
            int bgColor = (iconStyle == STYLE_COLOR)
                    ? ContextCompat.getColor(themedContext, colorRes[stateIdx])
                    : ContextCompat.getColor(themedContext, R.color.text_primary);
            int fgColor = ContextCompat.getColor(themedContext, R.color.text_outline) | 0xFF000000;
            icon.setBadgeText(badgeText, bgColor, fgColor);
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
    // Bluetooth state colours by ordinal (OFF, NO_DEVICE, CONNECTED).
    private static final int[] BT_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_off,
            R.color.status_bluetooth
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
        unregisterBluetoothReceiver();
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
