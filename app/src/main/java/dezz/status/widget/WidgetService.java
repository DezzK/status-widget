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
import android.text.TextUtils;
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

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
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

    /** Cross-fade duration for the entire overlay (show/hide / per-app hide). */
    private static final int OVERLAY_FADE_DURATION_MS = 500;
    /**
     * Duration of the combined Fade + ChangeBounds transition that handles per-brick
     * visibility flips. See {@link #beginVisibilityTransition} for the "window-buffer"
     * trick that makes this transition stay inside a stable window rectangle.
     */
    private static final int BRICK_TRANSITION_DURATION_MS = 450;
    /**
     * Duration of {@link android.animation.LayoutTransition#CHANGING} animations that fire
     * when a child changes its own size (clock minute, date, media track, icon swap). Shorter
     * than visibility flips because the user sees small frequent updates as snappy when
     * animated under ~300ms; longer feels sluggish for tiny shifts.
     */
    private static final int CONTENT_CHANGE_DURATION_MS = 250;
    /** Duration of the alpha animation used when a brick is hidden in keeps-space mode. */
    private static final int BRICK_ALPHA_DURATION_MS = 300;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_STATUS_CHECK_INTERVAL = 1000;
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    /** Cadence for advancing the media progress bar while a track is actively playing. 250ms
     *  is fast enough to look smooth on a thin bar and slow enough to not show up in profilers. */
    private static final long MEDIA_PROGRESS_TICK_MS = 250L;
    /** Gap between the play/pause indicator and the text it precedes, as a fraction of that
     *  text's size — same rationale as the icon's own size: it must track the font sliders. */
    private static final float STATE_ICON_GAP_RATIO = 0.25f;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 1000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
    private static final String GNSSSHARE_CLIENT_PACKAGE = "dezz.gnssshare.client";
    private static final String GNSSSHARE_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    /** Satellite count extra. A value of {@code -1} means "no satellite data" (badge hidden). */
    private static final String GNSSSHARE_EXTRA_SATELLITES_COUNT = "count";
    /**
     * Optional positioning-mode extra, treated as a bit mask (absent / 0 = normal satellite
     * fixing). The two flags are independent — dead reckoning and spoofing-detected can each be
     * set on their own or together (3 = dead reckoning entered because of a detected spoof).
     */
    private static final String GNSSSHARE_EXTRA_MODE = "mode";
    private static final int GNSSSHARE_MODE_DR = 1;     // bit 0: position is dead-reckoned
    private static final int GNSSSHARE_MODE_SPOOF = 2;  // bit 1: GPS spoofing detected
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

    /**
     * Number of in-flight transitions that have widened the WindowManager window to the
     * screen-width "buffer" so animations can play in a stable rectangle. Incremented when
     * a transition starts the buffer, decremented when it ends; the window is restored to
     * WRAP_CONTENT only when the counter reaches zero. Shared between:
     * <ul>
     *   <li>{@link #beginVisibilityTransition} (brick show/hide)</li>
     *   <li>The always-on {@link android.animation.LayoutTransition#CHANGING} on
     *       overlayContainer (any child changing measured size)</li>
     *   <li>The eager pre-empt in the {@code onLayoutChange} listener that catches a
     *       shrink one frame before {@code LayoutTransition.startTransition} would,
     *       so the window doesn't snap below the children that are still animating
     *       at their old positions</li>
     * </ul>
     */
    private int pendingBufferedTransitions = 0;

    /**
     * Closes the buffer opened eagerly by {@code onLayoutChange} when the content shrinks.
     * Posted with a delay slightly longer than {@link #BRICK_TRANSITION_DURATION_MS}; the
     * happy-path {@code LayoutTransition.endTransition} usually fires first and the
     * counter goes to zero on its own — this is the safety net for the case where no
     * {@code LayoutTransition} actually runs (e.g. a same-size measure that still
     * propagated through), so the window doesn't stay screen-wide forever.
     */
    private final Runnable shrinkBufferSafetyClose = this::endBufferedTransition;

    /**
     * Always-on {@link android.animation.LayoutTransition#CHANGING} animation installed on the
     * overlay container. Held as a field so {@link #beginVisibilityTransition} can disable
     * CHANGING for the duration of a visibility flip — otherwise the explicit ChangeBounds
     * inside the visibility {@link android.transition.TransitionSet} and the implicit CHANGING
     * triggered by sibling bricks shifting both play at once, producing the visible "double
     * animation". Re-enabled when the visibility transition's close runnable fires.
     */
    @Nullable
    private android.animation.LayoutTransition contentLayoutTransition;

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

    private int satellitesCount = -1;
    private int gnssModeFlags = 0;
    private long satellitesCountTimestamp = 0;
    private boolean satelliteReceiverRegistered = false;
    private final BroadcastReceiver satelliteStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra(GNSSSHARE_EXTRA_SATELLITES_COUNT, -1);
            int mode = intent.getIntExtra(GNSSSHARE_EXTRA_MODE, 0);
            Log.d(TAG, "GNSS Share satellites count: " + count + ", mode: " + mode);
            satellitesCount = count;
            gnssModeFlags = mode;
            // Monotonic clock (matches the postDelayed reset below), so a boot-time wall-clock
            // jump from GPS/NTP sync can't prematurely expire or freeze the freshness window.
            satellitesCountTimestamp = android.os.SystemClock.uptimeMillis();
            mainHandler.removeCallbacks(satellitesCountResetRunnable);
            mainHandler.postDelayed(satellitesCountResetRunnable, GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS);
            updateGnssStatus();
        }
    };
    private final Runnable satellitesCountResetRunnable = () -> {
        satellitesCount = -1;
        gnssModeFlags = 0;
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

        // Re-evaluate brick visibility when the car SDK's asynchronous service connect finally
        // answers whether the car sensors exist — critical on the boot-autostart path, where
        // the first applyPreferences runs before the vendor service is up and would otherwise
        // hide configured car bricks until the user happens to open the settings UI.
        CarIntegrations.get(this).setAvailabilityChangedListener(() -> {
            if (binding != null) applyPreferences();
        });

        createOverlayView();
    }

    private void createOverlayView() {
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        // Start invisible — the addView() below makes the window appear instantly; we then
        // fade the content in to match the symmetric fade-out the overlay does elsewhere.
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setVisibility(View.VISIBLE);
        // Listen on the INNER container, not the outer FrameLayout. During a visibility
        // transition we pre-expand the *window* (root) to screenWidth as a buffer for
        // TransitionManager; if we listened on the root we'd see that buffer expand as a
        // huge layout change and shove overlayX by hundreds of pixels (and persist it).
        // The inner container's bounds are what TransitionManager animates smoothly.
        binding.overlayContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            updateBackground();
            // Right-edge anchoring: when the widget content changes its measured width, shift the
            // window's left edge by the same amount so the right edge stays put. Done in a single
            // updateViewLayout to avoid the "shrink then slide" two-phase animation that
            // Gravity.RIGHT produces.
            if (params == null) return;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            boolean nonStatusBar = prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR;
            if (nonStatusBar
                    && prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
            notifyOverlayState();
        });

        // Synchronous "size about to change" hook. Fires from {@code onMeasure} of the
        // BufferingLinearLayout — earlier than OnLayoutChangeListener and earlier than
        // LayoutTransition.startTransition, both of which run after ViewRootImpl has
        // already pushed the new wrap_content dimensions to WindowManager. Catching it
        // mid-measure lets our updateViewLayout(screenWidth) win the race so the window
        // never snaps below the children that are about to animate. The safety runnable
        // is a fallback in case no LayoutTransition actually plays.
        binding.overlayContainer.setSizeChangeHint((oldW, newW, oldH, newH) -> {
            if (params == null) return;
            if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) return;
            if (newW >= oldW) return;   // grow path already works
            if (pendingBufferedTransitions > 0) return;   // some transition already buffering
            beginBufferedTransition(true);
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            mainHandler.postDelayed(shrinkBufferSafetyClose,
                    BRICK_TRANSITION_DURATION_MS + 200);
        });

        // Universal "content size changed" animation: install a LayoutTransition with only the
        // CHANGING type enabled on the overlay container. Any child that changes its measured
        // size (clock minute rolls over, date string flips at midnight, media title scrolls to
        // a new track, status icon swaps drawable) will produce a smooth ChangeBounds-style
        // animation for itself and any siblings it pushes around. CHANGE_APPEARING / APPEARING
        // / DISAPPEARING are left disabled — those cases are handled by our explicit
        // {@link #beginVisibilityTransition} that knows about the window-buffer trick.
        // We hook startTransition / endTransition into the same buffered-transition counter so
        // the window doesn't snap mid-animation when CHANGING runs solo, and so concurrent
        // CHANGING + visibility transitions coexist correctly.
        contentLayoutTransition = new android.animation.LayoutTransition();
        android.animation.LayoutTransition lt = contentLayoutTransition;
        lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(android.animation.LayoutTransition.CHANGING, CONTENT_CHANGE_DURATION_MS);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGING,
                new android.view.animation.AccelerateDecelerateInterpolator());
        lt.addTransitionListener(new android.animation.LayoutTransition.TransitionListener() {
            @Override
            public void startTransition(android.animation.LayoutTransition transition,
                                        android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                beginBufferedTransition(true);
            }

            @Override
            public void endTransition(android.animation.LayoutTransition transition,
                                      android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                endBufferedTransition();
            }
        });
        binding.overlayContainer.setLayoutTransition(lt);

        // Set up drag listener (just registers a touch listener on the root view — safe to do
        // before addView since the listener captures touches once attached).
        setupDragListener();

        // Initialize params and addView BEFORE applyPreferences. The first applyPreferences()
        // call inside this method walks through applyBrickVisibility / beginVisibilityTransition
        // which expects to expand the window via WindowManager.updateViewLayout — that requires
        // params and the view to be attached. Doing applyPreferences before addView used to
        // leave pendingBufferedTransitions stuck at 1 forever, which suppressed every later
        // shrink-side buffer pre-empt and made content-shrink animations clip their right edge.
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
            return;
        }

        applyPreferences();

        updateWifiStatus();
        updateGnssStatus();

        // Fade in the freshly-added view; addView itself is instant.
        binding.getRoot().animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .start();
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

        // The content-change LayoutTransition only makes sense in floating mode, where the
        // widget's own width animates as brick content grows/shrinks. In status-bar mode the
        // row is full-width with fixed groups — there is nothing to animate, but the CHANGING
        // tracker still arms itself on every layout pass of the container and on OEM head
        // units it visibly "regroups" the media row once a second (triggered by the periodic
        // GNSS/status redraws) while the marquee scrolls. Disable it entirely there.
        binding.overlayContainer.setLayoutTransition(
                prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR ? null : contentLayoutTransition);

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
        applyIndoorTempBrickSettings();
        applyOutdoorTempBrickSettings();

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
        // Padding goes on the INNER container — that's the view with the rounded background.
        // Putting it on the outer FrameLayout instead leaves a transparent gutter around the
        // background rect (visible at non-zero padding) and shifts the background's rounded
        // corners outside the touchable area.
        binding.overlayContainer.setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());

        // Lock the widget height to the tallest brick that's in the user's chosen order —
        // including bricks currently hidden per-app. Otherwise hiding e.g. a big Time brick
        // would let the row shrink vertically and the remaining icons would re-center up,
        // breaking alignment with the device status bar that users carefully tune.
        // {@code setMinimumHeight} compares against the view's *total* measured height (content
        // plus padding), so we add the vertical padding here — otherwise when the tallest brick
        // is visible the view measures to {@code maxBrick + padding} and when it's hidden it
        // collapses to {@code minHeight = maxBrick} (without padding), shrinking by the padding
        // amount on every hide.
        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(bricksSet) + verticalPadding);

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
                // Deliver callbacks on the main thread: they touch the overlay views and the
                // themedContext, which must not be read from the default ConnectivityThread.
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback, mainHandler);

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

        // Car temperature bricks — one subscription per brick through the flavor's
        // CarIntegration; the callback lands on the main thread per its contract.
        updateCarTempSubscription(BrickType.INDOOR_TEMP, bricksSet, binding.indoorTempText);
        updateCarTempSubscription(BrickType.OUTDOOR_TEMP, bricksSet, binding.outdoorTempText);
    }

    private void updateCarTempSubscription(BrickType type, Set<BrickType> bricksSet,
                                           OutlineTextView target) {
        CarIntegration car = CarIntegrations.get(this);
        if (bricksSet.contains(type)) {
            // Subscribe regardless of isBrickSupported(): right after boot the vendor service
            // may not have connected yet and support reads as "unknown/error" — but the SDK
            // queues listener registrations locally, so subscribing now means data starts
            // flowing the moment the service comes up. Visibility is gated separately in
            // applyBrickVisibility, and the availability-changed callback re-runs
            // applyPreferences when the support answer flips.
            if (target.getText().length() == 0) {
                // Placeholder until the first value arrives, so the brick occupies its slot
                // instead of rendering as a zero-width hole.
                target.setText(TEMP_PLACEHOLDER);
            }
            car.subscribe(type, (brickType, value) -> {
                if (binding == null) return;
                target.setText(formatTemperature(value));
            });
        } else {
            car.unsubscribe(type);
            // Reset so a re-added brick starts from the placeholder, not a stale reading.
            target.setText(TEMP_PLACEHOLDER);
        }
    }

    /** Last rendered media subtitle — used to distinguish a real track change from the
     *  once-a-second metadata republishes some players emit (see updateMediaInfo). */
    @Nullable
    private String lastMediaSubtitle = null;

    /** Shown while a subscribed temperature brick has not yet received a plausible value. */
    private static final String TEMP_PLACEHOLDER = "--°";

    /** {@code TextView.setText} drops the layout and forces a relayout even for identical text —
     *  callers on hot paths (per-second player callbacks) must skip unchanged values. */
    private static void setTextIfChanged(android.widget.TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private static String formatTemperature(float celsius) {
        // Integer rounding via Math.round avoids "%.0f"-style "-0°" for readings in (-0.5, 0).
        return Math.round(celsius) + "°";
    }

    private void reorderBricks(List<BrickType> bricks) {
        // Adding/removing a brick changes child order/membership of the root.
        // applyBrickVisibility() (called right after this from applyPreferences) drives the
        // per-brick fade + width animation that gives us the "dynamic island" feel; we
        // just rearrange children here.
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            reorderForStatusBar(bricks);
        } else {
            reorderForFloating(bricks);
        }
    }

    private void reorderForFloating(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
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
        LinearLayout root = binding.overlayContainer;
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
            case INDOOR_TEMP:
                return binding.indoorTempText;
            case OUTDOOR_TEMP:
                return binding.outdoorTempText;
            default:
                return null;
        }
    }

    private void applyTimeBrickSettings() {
        applySingleLineTextBrick(binding.timeText, prefs.time);
    }

    private void applyIndoorTempBrickSettings() {
        applySingleLineTextBrick(binding.indoorTempText, prefs.indoorTemp);
    }

    private void applyOutdoorTempBrickSettings() {
        applySingleLineTextBrick(binding.outdoorTempText, prefs.outdoorTemp);
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
        int textColor = ContextCompat.getColor(themedContext, R.color.text_primary);

        // Source line: independent font, opacity, outline.
        Typeface sourceTypeface = Fonts.resolve(this, prefs.media.sourceFontFamily.get(),
                prefs.media.sourceFontBold.get(), prefs.media.sourceFontItalic.get());
        binding.mediaAppText.setOutlineColor(textOutlineColor(prefs.media.sourceOutlineAlpha.get()));
        binding.mediaAppText.setOutlineWidth(prefs.media.sourceOutlineWidth.get());
        binding.mediaAppText.setTextColor(textColor);
        binding.mediaAppText.setTypeface(sourceTypeface);
        binding.mediaAppText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.sourceFontSize.get());
        binding.mediaAppText.setAlpha(prefs.media.sourceContentAlpha.get() / 255f);

        // Title line: existing media.* font + opacity + outline (TextBrickPrefs inherited).
        Typeface titleTypeface = Fonts.resolve(this, prefs.media.fontFamily.get(),
                prefs.media.fontBold.get(), prefs.media.fontItalic.get());
        binding.mediaTitleText.setOutlineColor(textOutlineColor(prefs.media.outlineAlpha.get()));
        binding.mediaTitleText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaTitleText.setTextColor(textColor);
        binding.mediaTitleText.setTypeface(titleTypeface);
        binding.mediaTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        binding.mediaTitleText.setAlpha(prefs.media.contentAlpha.get() / 255f);

        // Source line is always static + ellipsized; only the title scrolls. Source is short
        // and a constant moving marquee on it would be more distracting than helpful.
        binding.mediaAppText.setMarqueeEnabled(false);
        binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get());

        applyMediaStateIcon(textColor);

        // Duration text — independent font size / alpha / outline so the user can dial it down
        // (typically the duration is rendered smaller and dimmer than the track subtitle).
        binding.mediaDurationText.setTypeface(titleTypeface);
        binding.mediaDurationText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.durationFontSize.get());
        binding.mediaDurationText.setTextColor(textColor);
        binding.mediaDurationText.setOutlineColor(textOutlineColor(prefs.media.durationOutlineAlpha.get()));
        binding.mediaDurationText.setOutlineWidth(prefs.media.durationOutlineWidth.get());
        binding.mediaDurationText.setAlpha(prefs.media.durationContentAlpha.get() / 255f);

        applyHorizontalMargins(binding.mediaContainer, prefs.media.marginStart.get(), prefs.media.marginEnd.get());
        binding.mediaContainer.setTranslationY(prefs.media.adjustY.get());
        // Container alpha back to full — per-line alpha is set above so the two values don't
        // multiply through the parent.
        binding.mediaContainer.setAlpha(1f);
        applyMediaMaxWidth(binding.mediaAppText);
        applyMediaMaxWidth(binding.mediaTitleText);
        // Alignment applies to the two ROWS — they, not the text views, are the children of the
        // vertical container, and layout_gravity on a child of a horizontal LinearLayout only
        // ever moves it vertically.
        applyMediaChildAlignment(binding.mediaSourceRow, prefs.media.sourceAlignment.get());
        applyMediaChildAlignment(binding.mediaTitleRow, prefs.media.alignment.get());
        // Vertical gap between the two lines, applied as the title row's top margin.
        LinearLayout.LayoutParams titleLp =
                (LinearLayout.LayoutParams) binding.mediaTitleRow.getLayoutParams();
        titleLp.topMargin = prefs.media.lineGap.get();
        binding.mediaTitleRow.setLayoutParams(titleLp);
    }

    /**
     * Playback-state indicator. It lives at the head of the source row — "▶ Spotify" reads as one
     * statement — but the source line is optional, so when it's off the icon is re-parented to the
     * head of the title row instead of vanishing with its host. Either way it takes the size,
     * outline and opacity of the line it sits on, so it scales with that line's font-size slider
     * and flips colour with the widget theme like the text around it.
     */
    private void applyMediaStateIcon(int textColor) {
        boolean onSourceRow = prefs.media.showSource.get();
        LinearLayout host = onSourceRow ? binding.mediaSourceRow : binding.mediaTitleRow;
        ViewGroup parent = (ViewGroup) binding.mediaStateIcon.getParent();
        if (parent != host) {
            if (parent != null) parent.removeView(binding.mediaStateIcon);
            host.addView(binding.mediaStateIcon, 0);
        }

        int fontSize = onSourceRow ? prefs.media.sourceFontSize.get() : prefs.media.fontSize.get();
        int outlineAlpha = onSourceRow
                ? prefs.media.sourceOutlineAlpha.get() : prefs.media.outlineAlpha.get();
        int outlineWidth = onSourceRow
                ? prefs.media.sourceOutlineWidth.get() : prefs.media.outlineWidth.get();
        int contentAlpha = onSourceRow
                ? prefs.media.sourceContentAlpha.get() : prefs.media.contentAlpha.get();
        binding.mediaStateIcon.setTextSizePx(fontSize);
        binding.mediaStateIcon.setIconColor(textColor);
        binding.mediaStateIcon.setOutlineColor(textOutlineColor(outlineAlpha));
        binding.mediaStateIcon.setOutlineWidth(outlineWidth);
        binding.mediaStateIcon.setAlpha(contentAlpha / 255f);

        // Gap to the text scales with that text too — a fixed one would glue the icon to a 60px
        // source line and strand it next to a 12px one.
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) binding.mediaStateIcon.getLayoutParams();
        int gap = Math.round(fontSize * STATE_ICON_GAP_RATIO);
        if (lp.getMarginEnd() != gap) {
            lp.setMarginEnd(gap);
            binding.mediaStateIcon.setLayoutParams(lp);
        }
    }

    /**
     * Horizontal alignment of a single line within the vertical media container.
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

    private void applyMediaMaxWidth(MarqueeOutlineTextView view) {
        // The view itself toggles between WRAP_CONTENT (text fits) and a fixed maxWidth
        // (overflow + scrolling). All we need here is to tell it the upper bound.
        view.setMaxWidth(prefs.media.maxWidth.get());
    }

    private void applyWifiBrickSettings() {
        ViewGroup.LayoutParams ip = binding.wifiStatusIcon.getLayoutParams();
        ip.width = prefs.wifi.size.get();
        ip.height = prefs.wifi.size.get();
        binding.wifiStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.wifiStatusIcon, prefs.wifi.marginStart.get(), prefs.wifi.marginEnd.get());
        binding.wifiStatusIcon.setTranslationY(prefs.wifi.adjustY.get());
        binding.wifiStatusIcon.setAlpha(prefs.wifi.contentAlpha.get() / 255f);
    }

    private void applyGpsBrickSettings() {
        ViewGroup.LayoutParams ip = binding.gnssStatusIcon.getLayoutParams();
        ip.width = prefs.gps.size.get();
        ip.height = prefs.gps.size.get();
        binding.gnssStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.gnssStatusIcon, prefs.gps.marginStart.get(), prefs.gps.marginEnd.get());
        binding.gnssStatusIcon.setTranslationY(prefs.gps.adjustY.get());
        binding.gnssStatusIcon.setAlpha(prefs.gps.contentAlpha.get() / 255f);
    }

    private void applyBluetoothBrickSettings() {
        ViewGroup.LayoutParams ip = binding.bluetoothStatusIcon.getLayoutParams();
        ip.width = prefs.bluetooth.size.get();
        ip.height = prefs.bluetooth.size.get();
        binding.bluetoothStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.bluetoothStatusIcon,
                prefs.bluetooth.marginStart.get(), prefs.bluetooth.marginEnd.get());
        binding.bluetoothStatusIcon.setTranslationY(prefs.bluetooth.adjustY.get());
        binding.bluetoothStatusIcon.setAlpha(prefs.bluetooth.contentAlpha.get() / 255f);
    }

    private void applySingleLineTextBrick(OutlineTextView view, Preferences.TextBrickPrefs p) {
        view.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        view.setOutlineColor(textOutlineColor(p.outlineAlpha.get()));
        view.setOutlineWidth(p.outlineWidth.get());
        view.setTypeface(Fonts.resolve(this, p.fontFamily.get(), p.fontBold.get(), p.fontItalic.get()));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.fontSize.get());
        view.setTranslationY(p.adjustY.get());
        view.setAlpha(p.contentAlpha.get() / 255f);
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
        boolean dateActive = bricksSet.contains(BrickType.DATE)
                && (prefs.date.showDate.get() || prefs.date.showDayOfWeek.get());
        // Car bricks only render when the vehicle supports the sensor — a preset imported from
        // another car may list them in brickOrder, and an unsupported sensor would otherwise
        // leave a permanently frozen placeholder brick in the row.
        CarIntegration car = CarIntegrations.get(this);
        boolean indoorTempActive = bricksSet.contains(BrickType.INDOOR_TEMP)
                && car.isBrickSupported(BrickType.INDOOR_TEMP);
        boolean outdoorTempActive = bricksSet.contains(BrickType.OUTDOOR_TEMP)
                && car.isBrickSupported(BrickType.OUTDOOR_TEMP);
        BrickTarget[] targets = {
                resolveTarget(BrickType.TIME, bricksSet.contains(BrickType.TIME),
                        binding.timeText, prefs.time.contentAlpha.get()),
                resolveTarget(BrickType.DATE, dateActive,
                        binding.dateText, prefs.date.contentAlpha.get()),
                resolveTarget(BrickType.WIFI, bricksSet.contains(BrickType.WIFI),
                        binding.wifiStatusIcon, prefs.wifi.contentAlpha.get()),
                resolveTarget(BrickType.GPS, bricksSet.contains(BrickType.GPS),
                        binding.gnssStatusIcon, prefs.gps.contentAlpha.get()),
                resolveTarget(BrickType.BLUETOOTH, bricksSet.contains(BrickType.BLUETOOTH),
                        binding.bluetoothStatusIcon, prefs.bluetooth.contentAlpha.get()),
                resolveTarget(BrickType.INDOOR_TEMP, indoorTempActive,
                        binding.indoorTempText, prefs.indoorTemp.contentAlpha.get()),
                resolveTarget(BrickType.OUTDOOR_TEMP, outdoorTempActive,
                        binding.outdoorTempText, prefs.outdoorTemp.contentAlpha.get()),
        };

        // Media has the extra session gate, so we build its BrickTarget here.
        boolean mediaShouldBeGone = !bricksSet.contains(BrickType.MEDIA);
        boolean mediaHiddenByApp = !mediaShouldBeGone && isBrickHiddenByApp(BrickType.MEDIA);
        BrickTarget mediaTarget;
        if (mediaShouldBeGone) {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
        } else if (mediaHiddenByApp) {
            if (prefs.hideKeepsSpaceFor(BrickType.MEDIA).get()) {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE, 0f);
            } else {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
            }
        } else {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE,
                    prefs.media.contentAlpha.get() / 255f);
        }

        // Categorise the changes. Visibility flips (VISIBLE↔GONE) get the TransitionManager +
        // window-buffer treatment; pure alpha changes (keep-space mode where the brick stays
        // in the layout) just get a plain alpha animation.
        java.util.List<BrickTarget> visibilityFlips = new java.util.ArrayList<>();
        java.util.List<BrickTarget> alphaOnly = new java.util.ArrayList<>();
        boolean expanding = false;
        for (BrickTarget t : targets) {
            if (t.view.getVisibility() != t.visibility) {
                visibilityFlips.add(t);
                if (t.visibility == View.VISIBLE) expanding = true;
            } else if (t.visibility == View.VISIBLE) {
                alphaOnly.add(t);
            }
        }
        // Media too.
        if (mediaTarget.view.getVisibility() != mediaTarget.visibility) {
            visibilityFlips.add(mediaTarget);
            if (mediaTarget.visibility == View.VISIBLE) expanding = true;
        } else if (mediaTarget.visibility == View.VISIBLE && !mediaShouldBeGone
                && !mediaHiddenByApp) {
            // media stays visible — just bring metadata up to date (also might tweak its
            // alpha via the brick target below).
            updateMediaInfo();
        }

        if (!visibilityFlips.isEmpty()) {
            // Scene root for TransitionManager is the INNER container — the outer FrameLayout
            // gets resized to a screen-width buffer via WindowManager, and we want the
            // transition to play inside the stable inner LinearLayout, not chase the buffer.
            beginVisibilityTransition(binding.overlayContainer, expanding);
        }

        // Apply all targets. For visibility flips Fade transition handles the alpha animation;
        // for alpha-only ones we run an explicit ViewPropertyAnimator.
        for (BrickTarget t : targets) {
            applyBrickTarget(t, visibilityFlips.contains(t));
        }
        applyBrickTarget(mediaTarget, visibilityFlips.contains(mediaTarget));

        // Per-brick alpha not covered by the Fade transition (keep-space VISIBLE→VISIBLE).
        // The bricks in alphaOnly might still want a visible-alpha update if contentAlpha
        // pref changed — handled by applyXxxBrickSettings setAlpha which runs before this.
    }

    /** Snapshot of the desired end state for a brick view. */
    private static final class BrickTarget {
        final View view;
        final int visibility;
        /** Target alpha when {@link #visibility} is {@code VISIBLE}; ignored otherwise. */
        final float visibleAlpha;
        BrickTarget(View view, int visibility, float visibleAlpha) {
            this.view = view;
            this.visibility = visibility;
            this.visibleAlpha = visibleAlpha;
        }
    }

    /**
     * Decide the final view state for a brick. {@code activeInLayout=false} (brick not in
     * the layout / Date with both flags off) → {@code GONE}, hard collapse. Otherwise honour
     * {@link Preferences#hideKeepsSpaceFor}: if true, render an INVISIBLE-equivalent (VISIBLE
     * view, alpha animated to 0); if false, plain GONE.
     */
    private BrickTarget resolveTarget(BrickType type, boolean activeInLayout, View view,
                                      int contentAlphaPref) {
        float baseAlpha = contentAlphaPref / 255f;
        if (!activeInLayout) {
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        if (isBrickHiddenByApp(type)) {
            if (prefs.hideKeepsSpaceFor(type).get()) {
                // VISIBLE-with-alpha-0 replaces the old INVISIBLE constant — same effect on
                // layout (space preserved) but animatable.
                return new BrickTarget(view, View.VISIBLE, 0f);
            }
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        return new BrickTarget(view, View.VISIBLE, baseAlpha);
    }

    /**
     * Applies a brick's target state. For visibility flips the heavy lifting is done by the
     * {@code TransitionManager} scene set up by {@link #beginVisibilityTransition} — we
     * just toggle {@code setVisibility} and the Fade transition cross-fades alpha while
     * ChangeBounds slides siblings into place. For alpha-only changes (keep-space hide)
     * we animate alpha explicitly.
     */
    private void applyBrickTarget(BrickTarget target, boolean handledByTransition) {
        if (target.visibility == View.GONE) {
            target.view.animate().cancel();
            target.view.setVisibility(View.GONE);
            return;
        }
        target.view.setVisibility(View.VISIBLE);
        if (handledByTransition) {
            // Fade transition animates the alpha for us; make sure the final value is the
            // brick's contentAlpha pref (not 1.0 from Fade's default).
            target.view.setAlpha(target.visibleAlpha);
        } else {
            target.view.animate().cancel();
            target.view.animate()
                    .alpha(target.visibleAlpha)
                    .setDuration(BRICK_ALPHA_DURATION_MS)
                    .start();
        }
    }

    /**
     * Runs the "buffer window" animation. Trick: before triggering the
     * scene change we either expand the window to screen width (when something is about to
     * appear) or pin it to its current width (when something is about to disappear). With
     * the window's outer rectangle frozen the children's Fade + ChangeBounds animations
     * play cleanly inside it; the listener restores the window to WRAP_CONTENT after the
     * transition so it snaps to the new natural size in one go. This sidesteps the
     * per-frame {@code updateViewLayout} approach that was visually broken on real hardware.
     */
    private void beginVisibilityTransition(ViewGroup sceneRoot, boolean expanding) {
        if (binding == null) return;
        beginBufferedTransition(expanding);

        // Suppress the always-on CHANGING animation for the duration of this visibility flip.
        // Sibling bricks shift positions when a brick appears/disappears, which LayoutTransition
        // would otherwise interpret as a content change and animate in parallel with our own
        // explicit ChangeBounds inside the TransitionSet — visible as a doubled motion.
        if (contentLayoutTransition != null) {
            contentLayoutTransition.disableTransitionType(
                    android.animation.LayoutTransition.CHANGING);
        }

        android.transition.TransitionSet tx = new android.transition.TransitionSet();
        tx.addTransition(new android.transition.ChangeBounds());
        tx.addTransition(new android.transition.Fade());
        tx.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        tx.setDuration(BRICK_TRANSITION_DURATION_MS);
        tx.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        // Listener can leak the buffer counter if TransitionManager decides nothing
        // animatable changed and never fires the lifecycle callbacks — known foot-gun.
        // Guard with a single-shot close flag and a safety runnable that runs unconditionally
        // after slightly longer than the transition's own duration. Whichever fires first
        // closes the buffer; the other becomes a no-op.
        final boolean[] closed = {false};
        Runnable closeOnce = () -> {
            if (closed[0]) return;
            closed[0] = true;
            if (contentLayoutTransition != null) {
                contentLayoutTransition.enableTransitionType(
                        android.animation.LayoutTransition.CHANGING);
            }
            endBufferedTransition();
        };
        tx.addListener(new android.transition.Transition.TransitionListener() {
            @Override public void onTransitionStart(android.transition.Transition t) {}
            @Override public void onTransitionEnd(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionCancel(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionPause(android.transition.Transition t) {}
            @Override public void onTransitionResume(android.transition.Transition t) {}
        });
        android.transition.TransitionManager.beginDelayedTransition(sceneRoot, tx);
        mainHandler.postDelayed(closeOnce, BRICK_TRANSITION_DURATION_MS + 500);
    }

    /**
     * Open a window-buffered transition: if no other buffered transition is in flight, pre-resize
     * the WindowManager window to either screen width ({@code expanding}) or its current width
     * (shrinking), so the animation that follows plays inside a stable rectangle instead of
     * fighting wrap-content. Idempotent under nesting: re-entrant callers just bump the counter.
     */
    private void beginBufferedTransition(boolean expanding) {
        if (binding == null) return;
        if (pendingBufferedTransitions++ == 0) {
            if (params != null && prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                int oldWidth = params.width;
                if (expanding) {
                    params.width = getResources().getDisplayMetrics().widthPixels;
                } else {
                    int currentWidth = binding.getRoot().getWidth();
                    if (currentWidth > 0) params.width = currentWidth;
                }
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                    params.width = oldWidth;
                }
            }
        }
    }

    /** Closes a transition opened by {@link #beginBufferedTransition}. When the last in-flight
     *  transition ends, restores the window to WRAP_CONTENT so it snaps to natural size. */
    private void endBufferedTransition() {
        if (pendingBufferedTransitions <= 0) return;
        if (--pendingBufferedTransitions == 0) {
            restoreWindowToWrapContent();
        }
    }

    private void restoreWindowToWrapContent() {
        if (params == null || binding == null) return;
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {}
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
            // Source and title can have different font sizes now, so sum them up properly when
            // both lines are shown; otherwise just the title line.
            int titleHeight = textLineHeight(binding.mediaTitleText, prefs.media.fontSize.get());
            int mediaHeight = titleHeight;
            if (prefs.media.showSource.get()) {
                int sourceHeight = textLineHeight(binding.mediaAppText,
                        prefs.media.sourceFontSize.get());
                mediaHeight = sourceHeight + titleHeight + prefs.media.lineGap.get();
            }
            h = Math.max(h, mediaHeight);
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
        // Car bricks only contribute to the height floor when the vehicle actually renders them
        // (same isBrickSupported gate as applyBrickVisibility) — otherwise a preset from another
        // car would inflate the widget height for bricks that never appear.
        CarIntegration car = CarIntegrations.get(this);
        if (bricks.contains(BrickType.INDOOR_TEMP) && car.isBrickSupported(BrickType.INDOOR_TEMP)) {
            h = Math.max(h, textLineHeight(binding.indoorTempText, prefs.indoorTemp.fontSize.get()));
        }
        if (bricks.contains(BrickType.OUTDOOR_TEMP) && car.isBrickSupported(BrickType.OUTDOOR_TEMP)) {
            h = Math.max(h, textLineHeight(binding.outdoorTempText, prefs.outdoorTemp.fontSize.get()));
        }
        return h;
    }

    private static int textLineHeight(OutlineTextView view, int fontSizePx) {
        // Copy so we don't mutate the live drawing paint. The copy preserves typeface, which is
        // crucial because Roboto Condensed Medium has different metrics from the default.
        Paint p = new Paint(view.getPaint());
        p.setTextSize(fontSizePx);
        Paint.FontMetrics fm = p.getFontMetrics();
        // All text TextViews in the widget have includeFontPadding=false — layout bounds use
        // ascent/descent (just the glyph metrics, no extra accent/descender reserve).
        return (int) Math.ceil(fm.descent - fm.ascent);
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
        // During a buffered transition the window is intentionally pinned wider than
        // wrap_content so children can animate without being clipped. Overwriting
        // params.width here would snap the window mid-animation and also strand the
        // TransitionManager listener (no scene change → no onTransitionEnd → counter
        // leak). The buffer closer will restore wrap_content when it ends.
        if (pendingBufferedTransitions > 0 && !statusBar) {
            newWidth = params.width;
        }
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
            stopMediaProgressTicker();
            return;
        }
        MediaController playing = pickActiveMediaController();
        if (playing == null) {
            binding.mediaContainer.setVisibility(View.GONE);
            stopMediaProgressTicker();
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
        boolean titleFirst = prefs.media.titleFirst.get();
        String first = titleFirst ? title : artist;
        String second = titleFirst ? artist : title;
        if (!isEmpty(first) && !isEmpty(second)) {
            subtitle = first + " — " + second;
        } else if (!isEmpty(title)) {
            subtitle = title;
        } else if (!isEmpty(artist)) {
            subtitle = artist;
        } else {
            // Something is playing but the player exposes no metadata at all — at least show a
            // placeholder so the user can see that media playback is active.
            subtitle = getString(R.string.media_unknown_track);
        }
        PlaybackState playbackState = playing.getPlaybackState();
        // Pause shape only for an actual PAUSED; transient states (buffering / seeking) keep the
        // play shape so the icon doesn't flicker every time the user scrubs.
        // Players republish PlaybackState continuously (Yandex Music every second), and
        // TextView.setText unconditionally drops its layout and requests a full re-layout even
        // for identical text. On OEM head units that per-second layout storm makes the whole
        // title row visibly jitter while the marquee scrolls — so every setter here must be
        // a no-op when the value didn't actually change (MediaStateIconView.setPaused is).
        binding.mediaStateIcon.setPaused(playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PAUSED);
        binding.mediaAppText.setMarqueeText(getAppLabel(playing.getPackageName()));
        // The whole row, not just the label — the indicator rides in it. Nothing is lost when it
        // goes: applyMediaStateIcon has already moved the icon over to the title row.
        binding.mediaSourceRow.setVisibility(prefs.media.showSource.get() ? View.VISIBLE : View.GONE);
        binding.mediaTitleText.setMarqueeText(subtitle);

        // Duration: format ms → "M:SS" / "H:MM:SS". Hidden when the user opted out or the
        // player doesn't expose a positive duration (live streams, podcast pre-buffer).
        long durationMs = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        // Track identity: duration/progress visibility may only COLLAPSE on a real track
        // change. Players republish metadata continuously (Yandex Music: every second) and
        // the duration is transiently absent in some republishes — hiding on those blips
        // collapsed the row height once a second, which read as the whole widget "regrouping"
        // while the marquee scrolls.
        boolean trackChanged = !TextUtils.equals(subtitle, lastMediaSubtitle);
        lastMediaSubtitle = subtitle;

        if (!prefs.media.showDuration.get()) {
            binding.mediaDurationText.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            // Leading space gives the gap between title and duration without an extra layout
            // margin pref — scales naturally with the duration font size.
            setTextIfChanged(binding.mediaDurationText, " " + formatTrackDuration(durationMs));
            binding.mediaDurationText.setVisibility(View.VISIBLE);
        } else if (trackChanged) {
            // New track with no usable duration (live stream) — hide for real.
            binding.mediaDurationText.setVisibility(View.GONE);
        }
        // else: transient blip on the same track — keep the last shown value.

        // Progress bar visibility is decided here ONLY (updateMediaProgress never touches it —
        // see the comment there). Same blip-tolerant policy as the duration text.
        if (!prefs.media.progressBarEnabled.get()) {
            binding.mediaProgressBar.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            if (binding.mediaProgressBar.getVisibility() != View.VISIBLE) {
                binding.mediaProgressBar.setColor(
                        ContextCompat.getColor(themedContext != null ? themedContext : this,
                                R.color.text_primary));
                binding.mediaProgressBar.setVisibility(View.VISIBLE);
            }
        } else if (trackChanged) {
            binding.mediaProgressBar.setVisibility(View.GONE);
        }

        binding.mediaContainer.setVisibility(View.VISIBLE);

        updateMediaProgress(playing);
    }

    /**
     * Format a positive duration in milliseconds as {@code M:SS} (under an hour) or
     * {@code H:MM:SS} (one hour or longer). Locale-independent — uses the same digit forms
     * everywhere because the duration is displayed alongside the marquee subtitle, where
     * regional digit substitutions would look out of place.
     */
    private static String formatTrackDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /**
     * Snap the progress bar to the current playback position and arm/disarm the periodic ticker.
     * Called both from {@link #updateMediaInfo} (state/metadata flips) and from
     * {@link #mediaProgressTick} (every ~250ms while playing) to advance the bar smoothly.
     */
    private void updateMediaProgress(@Nullable MediaController playing) {
        if (binding == null) return;
        // Visibility policy: this method NEVER changes the bar's visibility. Flipping
        // GONE/VISIBLE changes the media container's height and relayouts the whole brick
        // row — and players like Yandex Music republish state/metadata every second, with
        // the duration transiently missing, which turned that flip into a once-a-second
        // visible "regroup" of the row while the marquee scrolls. Visibility is decided
        // solely in updateMediaInfo (real track/state changes); here we only advance the
        // fill fraction — a pure repaint.
        if (!prefs.media.progressBarEnabled.get() || playing == null
                || binding.mediaProgressBar.getVisibility() != View.VISIBLE) {
            stopMediaProgressTicker();
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        long duration = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        PlaybackState state = playing.getPlaybackState();
        if (duration <= 0L || state == null) {
            // Timeline transiently unavailable (metadata republish in flight) — keep the last
            // rendered fill and let the next tick catch up rather than touching layout.
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long lastUpdate = state.getLastPositionUpdateTime();
        long basePosition = state.getPosition();
        // PlaybackState.getPosition() returns the position as of getLastPositionUpdateTime();
        // for the *current* moment we extrapolate with the reported playback speed (typically 1.0).
        long actualPosition = basePosition
                + (long) ((now - lastUpdate) * state.getPlaybackSpeed());
        if (actualPosition < 0L) actualPosition = 0L;
        if (actualPosition > duration) actualPosition = duration;

        binding.mediaProgressBar.setProgress((float) actualPosition / (float) duration);

        if (state.getState() == PlaybackState.STATE_PLAYING) {
            // Re-arm — the new postDelayed replaces any previously queued one, idempotent.
            mainHandler.removeCallbacks(mediaProgressTick);
            mainHandler.postDelayed(mediaProgressTick, MEDIA_PROGRESS_TICK_MS);
        } else {
            stopMediaProgressTicker();
        }
    }

    private void stopMediaProgressTicker() {
        mainHandler.removeCallbacks(mediaProgressTick);
    }

    private final Runnable mediaProgressTick = () -> updateMediaProgress(pickActiveMediaController());

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
        // Prefer a controller that is currently playing. If none is playing, fall back to any
        // controller in a transient "media is loaded and the user is doing something with it"
        // state — paused, buffering, fast-forwarding, rewinding, skipping. Keeping the brick
        // visible across these short-lived transitions avoids a VISIBLE→GONE→VISIBLE blink
        // (which would re-layout the title text from zero size and reset the marquee scroll)
        // every time the user seeks or the player briefly buffers.
        MediaController fallback = null;
        for (MediaController c : activeMediaControllers) {
            PlaybackState s = c.getPlaybackState();
            if (s == null) continue;
            int state = s.getState();
            if (state == PlaybackState.STATE_PLAYING) {
                return c;
            }
            if (fallback == null && isMediaActiveState(state)) {
                fallback = c;
            }
        }
        return fallback;
    }

    private static boolean isMediaActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_CONNECTING:
                return true;
            default:
                return false;
        }
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
        satellitesCount = -1;
        gnssModeFlags = 0;
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
     * Seed the connected-device set from whatever the system can synchronously tell us, with
     * an async profile-proxy refresh on top.
     * <p>
     * The synchronous path iterates {@link BluetoothAdapter#getBondedDevices()} and reflects on
     * the hidden {@code BluetoothDevice.isConnected()} method — this works on AOSP and the
     * typical car-HU ROMs derived from it, returns instantly, and crucially covers the
     * "brick was just added, BT is already on and the device is paired" case that pure
     * profile-proxy seeding misses.
     * <p>
     * The async path keeps querying HEADSET / A2DP proxies as a safety net for OEM ROMs where
     * the reflection trick is unavailable, and for unbonded but momentarily connected devices.
     * ACL_CONNECTED / ACL_DISCONNECTED broadcasts (registered separately) handle live updates
     * once the receiver is in place.
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

        seedConnectedFromBondedDevices(adapter);

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

    /**
     * Synchronously populate {@link #btConnectedAddrs} from bonded devices via the hidden
     * {@code BluetoothDevice.isConnected()} method. Safe to call repeatedly — the set is a
     * union, so a stale entry would only be cleared by the ACL_DISCONNECTED broadcast or by
     * a full Bluetooth-off transition.
     */
    private void seedConnectedFromBondedDevices(BluetoothAdapter adapter) {
        java.lang.reflect.Method isConnected;
        try {
            isConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (NoSuchMethodException nsm) {
            return;
        } catch (Throwable t) {
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (Throwable t) {
            return;
        }
        if (bonded == null) return;
        for (BluetoothDevice device : bonded) {
            if (device == null || device.getAddress() == null) continue;
            try {
                Object result = isConnected.invoke(device);
                if (result instanceof Boolean && (Boolean) result) {
                    btConnectedAddrs.add(device.getAddress());
                }
            } catch (Throwable ignored) {
            }
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
        boolean needTracking = !hiddenInPackages.isEmpty() || anyBrickHasHideList();
        boolean accessibilityActive = WidgetAccessibilityService.getInstance() != null;
        boolean usageGranted = Permissions.isUsageAccessGranted(this);
        // Two paths to the foreground package:
        //   - AccessibilityService (preferred): per-display data, multi-display safe.
        //   - UsageStatsManager (fallback): global, single foreground across all displays.
        // We only poll when neither path is being driven by events: the accessibility service
        // pushes via {@link #onForegroundDisplayMapUpdated()}, no polling needed.
        boolean shouldPoll = needTracking && !accessibilityActive && usageGranted;
        if (needTracking && (accessibilityActive || usageGranted)) {
            if (usageGranted && usageStatsManager == null) {
                usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            }
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            if (shouldPoll) {
                mainHandler.post(foregroundAppCheckRunnable);
            }
            // If accessibility just connected, recompute once now — we won't get an event
            // until something actually changes on a display.
            if (accessibilityActive) {
                checkForegroundApp();
            }
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            applyOverlayVisibility(false);
        }
    }

    /**
     * Called by {@link WidgetAccessibilityService} when the per-display foreground map changes.
     * Recomputes visibility based on the package on <i>our</i> display.
     */
    public void onForegroundDisplayMapUpdated() {
        mainHandler.post(this::checkForegroundApp);
    }

    /**
     * Called by {@link WidgetAccessibilityService} when its connection state flips — connect
     * or disconnect. Re-evaluates which foreground-tracking pipeline to use (accessibility
     * push vs. UsageStats poll).
     */
    public void onForegroundTrackingPathChanged() {
        mainHandler.post(this::updateForegroundAppTracking);
    }

    private void checkForegroundApp() {
        if (hiddenInPackages.isEmpty() && !anyBrickHasHideList()) return;

        WidgetAccessibilityService a11y = WidgetAccessibilityService.getInstance();
        String latestPackage;
        if (a11y != null) {
            // Display-aware: look up the foreground package on our overlay's display only.
            // If the accessibility framework hasn't reported anything for that display yet,
            // fall through to the UsageStats path so we're not blind on first start.
            int myDisplayId = currentOverlayDisplayId();
            latestPackage = a11y.getForegroundPackageOnDisplay(myDisplayId);
            if (latestPackage == null && usageStatsManager != null
                    && Permissions.isUsageAccessGranted(this)) {
                latestPackage = latestPackageFromUsageStats();
            }
        } else {
            // Global path — works on single-display devices.
            if (usageStatsManager == null) return;
            if (!Permissions.isUsageAccessGranted(this)) {
                updateForegroundAppTracking();
                return;
            }
            latestPackage = latestPackageFromUsageStats();
        }
        if (latestPackage == null) return;

        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
        if (changed && binding != null) {
            applyBrickVisibility(currentBrickSet());
        }
    }

    /** Display ID our overlay's window is attached to. Defaults to {@code DEFAULT_DISPLAY}
     *  if we can't determine it (single-display devices or pre-attach). */
    private int currentOverlayDisplayId() {
        if (binding == null) return android.view.Display.DEFAULT_DISPLAY;
        android.view.Display display = binding.getRoot().getDisplay();
        return display != null ? display.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
    }

    /** Extracts the most recent foreground package from {@link UsageStatsManager}. Null if
     *  nothing was reported in the lookback window. */
    @Nullable
    private String latestPackageFromUsageStats() {
        if (usageStatsManager == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - FOREGROUND_APP_LOOKBACK_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latest = lastForegroundPackage;
        long latestTimestamp = 0;
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latest = event.getPackageName();
                }
            }
        }
        return latest;
    }

    private void applyOverlayVisibility(boolean hide) {
        if (overlayHiddenByApp == hide) {
            return;
        }
        overlayHiddenByApp = hide;
        if (binding == null) return;
        View root = binding.getRoot();
        root.animate().cancel();
        if (hide) {
            // Animate to fully transparent, then collapse so the window stops occupying space.
            root.animate()
                    .alpha(0f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .withEndAction(() -> {
                        if (overlayHiddenByApp) root.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            // The animate().cancel() above leaves alpha at whatever it was mid-animation;
            // start the fade-in from the current value to its target of 1f.
            root.setVisibility(View.VISIBLE);
            root.animate()
                    .alpha(1f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .start();
        }
    }

    private void updateBackground() {
        if (binding == null) {
            return;
        }
        if (themedContext == null) {
            updateThemedContext();
        }
        // Read from the inner container, which is where the background drawable lives and what
        // TransitionManager animates. Reading from getRoot() would, during a visibility
        // transition, briefly return the screen-width window buffer and cap maxRadius too high.
        int width = binding.overlayContainer.getWidth();
        int height = binding.overlayContainer.getHeight();
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
        // themedContext is momentarily null between onConfigurationChanged (which invalidates it)
        // and the next applyPreferences that rebuilds it. A status update landing in that window
        // must not crash, so fall back to the service context (matches the guard at getOutlineColor).
        Context ctx = themedContext != null ? themedContext : this;
        int tint = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(ctx, colorRes[stateIdx])
                : ContextCompat.getColor(ctx, R.color.text_primary);
        // Skip the no-op tint set: applyImageTint invalidates the drawable unconditionally,
        // and this runs on every periodic status broadcast.
        ColorStateList currentTint = ImageViewCompat.getImageTintList(icon);
        if (currentTint == null || currentTint.getDefaultColor() != tint) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));
        }

        int outlineAlpha = iconPrefs.outlineAlpha.get();
        if (outlineAlpha > 0) {
            int haloColor = (ContextCompat.getColor(ctx, R.color.text_outline) & 0x00FFFFFF)
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

        // Text badge: GNSS satellite count / DR / spoof marker for GPS, connected-device count
        // for Bluetooth.
        String badgeText = null;
        int badgeBg = 0;
        // Foreground defaults to the widget text colour (flips with the theme, pairs with the
        // style-driven backgrounds below); the coloured GNSS markers override it to a fixed dark
        // ink so the label stays legible on their amber / red pills (white on amber is ~1.9:1).
        int badgeFg = ContextCompat.getColor(ctx, R.color.text_outline) | 0xFF000000;
        // Default badge background follows the icon's own colouring; the GNSS markers override it
        // below with a fixed semantic colour so the meaning reads the same in both icon styles.
        int styleBg = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(ctx, colorRes[stateIdx])
                : ContextCompat.getColor(ctx, R.color.text_primary);
        if (iconType == ICON_TYPE_GNSS && prefs.gps.showSatelliteBadge.get()
                && android.os.SystemClock.uptimeMillis() - satellitesCountTimestamp < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
            // Two independent flags: dead reckoning drives the text, spoofing drives the colour,
            // so both read off the same pill (e.g. "DR" on red = fell back to DR because of a spoof).
            boolean deadReckoning = (gnssModeFlags & GNSSSHARE_MODE_DR) != 0;
            boolean spoofDetected = (gnssModeFlags & GNSSSHARE_MODE_SPOOF) != 0;
            if (deadReckoning) {
                badgeText = getString(R.string.gnss_dr_badge);
            } else if (spoofDetected) {
                // Spoofing but still on GPS: show the marker, not the count — the count is
                // untrustworthy under a spoof and may be absent (some clients report -1).
                badgeText = getString(R.string.gnss_spoof_badge);
            } else if (satellitesCount > 0) {
                badgeText = String.valueOf(satellitesCount);
            }
            if (badgeText != null) {
                if (spoofDetected) {
                    // Spoofing detected — red, whether we're on DR or still on GPS.
                    badgeBg = ContextCompat.getColor(ctx, R.color.status_error);
                    badgeFg = ContextCompat.getColor(ctx, R.color.status_badge_text);
                } else if (deadReckoning) {
                    // Dead reckoning without a spoof — amber (degraded, not an attack).
                    badgeBg = ContextCompat.getColor(ctx, R.color.status_warning);
                    badgeFg = ContextCompat.getColor(ctx, R.color.status_badge_text);
                } else {
                    badgeBg = styleBg;
                }
            }
        } else if (iconType == ICON_TYPE_BT && prefs.bluetooth.showDeviceCountBadge.get()
                && bluetoothState == BluetoothState.CONNECTED && !btConnectedAddrs.isEmpty()) {
            badgeText = String.valueOf(btConnectedAddrs.size());
            badgeBg = styleBg;
        }
        if (badgeText != null) {
            icon.setBadgeText(badgeText, badgeBg, badgeFg);
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
        mainHandler.removeCallbacks(mediaProgressTick);

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
        // Drop car sensor subscriptions but keep the process-wide integration alive — the
        // settings UI may still query isBrickSupported after the overlay service stops.
        CarIntegration car = CarIntegrations.get(this);
        car.setAvailabilityChangedListener(null);
        car.unsubscribe(BrickType.INDOOR_TEMP);
        car.unsubscribe(BrickType.OUTDOOR_TEMP);
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
