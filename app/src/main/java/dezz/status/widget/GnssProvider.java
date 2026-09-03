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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one subscription to everything positional: the platform's location fixes, the GNSS status
 * callback, and the companion gnss-share app's broadcast. Bricks read it and are told when it
 * changes; no brick registers a receiver or a location listener of its own.
 *
 * <h3>Why one object rather than one per brick</h3>
 * Two bricks want overlapping slices of the same three feeds — the GPS icon wants fixes and the
 * badge's mode bits, the readout wants fixes, satellite counts and the same mode bits. Registering
 * per brick would mean two location listeners keeping the GNSS engine hot, two copies of the
 * gnss-share bit layout (a contract with another app, so it may exist once), and two 1 Hz tickers.
 *
 * <h3>Lifetime</h3>
 * Owned by the service, not by a brick, and reached through {@link WidgetHost#gnss()}. That is
 * deliberate: a shared object owned by whichever brick happened to be created first would outlive
 * every service stop the settings-import path performs, because teardown iterates the brick
 * registry. {@link WidgetService#onDestroy} calls {@link #destroy()} explicitly.
 *
 * <h3>Subscriptions</h3>
 * Each feed is registered only while some listener has declared it needed — see
 * {@link #setNeeds}. A brick that is not in the user's order, or whose relevant switches are all
 * off, therefore costs nothing. Listeners are told about events with the SHAPE the platform
 * delivered them in rather than a bare "something changed": the GPS icon's demotion ladder is a
 * state machine over {@code onStarted} / {@code onStopped} / fix arrival, and flattening those
 * into one callback would force it to be re-derived from state it no longer has.
 */
final class GnssProvider {

    /** What a listener wants registered. Bit flags, OR-ed, declared through {@link #setNeeds}. */
    static final int NEED_NONE = 0;
    /** Platform location fixes: accuracy, speed, altitude, bearing, fix age. */
    static final int NEED_LOCATION = 1;
    /**
     * The {@link GnssStatus} callback — the engine's started / stopped / first-fix transitions.
     *
     * <p>Its satellite counts are deliberately NOT surfaced. They describe the HEAD UNIT's own
     * antenna, while the position on screen comes from the phone through gnss-share's mock
     * provider; with that provider serving, the platform engine is never even started and the
     * counts stay at "nothing reported" forever. The satellites the readout shows therefore come
     * from the broadcast, where both numbers describe one receiver.
     */
    static final int NEED_GNSS_STATUS = 2;
    /** The companion app's broadcast: its satellite count and the positioning-mode bits. */
    static final int NEED_SHARE = 4;

    /**
     * Everything a brick can be told. Default methods, so a listener overrides only the feeds it
     * asked for — a brick that needs no satellite counts should not have to write an empty
     * method to say so.
     */
    interface Listener {
        /** A fix arrived. */
        default void onGnssLocation(@NonNull Location location) {
        }

        /** The GNSS engine started producing. */
        default void onGnssStarted() {
        }

        /** The GNSS engine stopped. */
        default void onGnssStopped() {
        }

        /** First fix of this session, with its time to first fix. */
        default void onGnssFirstFix(int ttffMillis) {
        }

        /** gnss-share data arrived, or its freshness window closed. */
        default void onGnssShareStatus() {
        }

        /**
         * One second passed. The one ticker in the app for things that go stale on their own — a
         * fix that stops arriving, a fix age counting up — so neither brick runs its own.
         */
        default void onGnssTick() {
        }
    }

    private static final String TAG = "GnssProvider";
    private static final long TICK_INTERVAL_MS = 1000;

    /** Broadcast from the companion gnss-share app; see its README for the contract. */
    private static final String SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    /** Satellite count extra. {@code -1} means "no satellite data". */
    private static final String EXTRA_SATELLITES_COUNT = "count";
    /**
     * Optional "satellites used in the fix" extra, added to the contract after {@code count}.
     * {@code -1} — including a sender that predates it and therefore omits it entirely — means
     * "not reported", and the readout falls back to the plain count. Both numbers describe the
     * SAME receiver, which is the whole point: the platform's own {@code GnssStatus} counts the
     * head unit's antenna, while the position being rendered comes from the phone.
     */
    private static final String EXTRA_USED_IN_FIX = "usedInFix";
    /**
     * Optional positioning-mode extra, treated as a bit mask (absent / 0 = normal satellite
     * fixing). The flags are independent — dead reckoning and spoofing-detected can each be
     * set on their own or together (3 = dead reckoning entered because of a detected spoof).
     */
    private static final String EXTRA_MODE = "mode";
    private static final int MODE_DR = 1;          // bit 0: position is dead-reckoned
    private static final int MODE_SPOOF = 2;       // bit 1: GPS spoofing detected
    /**
     * Bits 2 and 3 carry the map matcher's road anchoring, and it takes two of them because the
     * answer has three values: no verdict at all (no map data loaded, a frozen matcher, an engine
     * that stopped), a verdict of "off the road", and one of "anchored to a road". Only the third
     * counts as anchored — an absent {@code mode} extra, or an older gnss-share that predates
     * these bits, therefore reads as "nothing claimed" rather than as "off the road".
     */
    private static final int MODE_ROAD_KNOWN = 4;  // bit 2: the road verdict below is live
    private static final int MODE_ON_ROAD = 8;     // bit 3: …and it says the position is on a road

    private static final long SHARE_TIMEOUT_MS = 30_000L;

    private final Context context;
    private final Handler handler;

    /**
     * Insertion-ordered so dispatch order is stable across runs. Iterated over a copy — a listener
     * is free to change its needs from inside a callback, which re-enters this map.
     */
    private final Map<Listener, Integer> listeners = new LinkedHashMap<>();

    @Nullable
    private LocationManager locationManager;
    private boolean locationRegistered;
    private boolean gnssStatusRegistered;
    private boolean shareRegistered;
    private boolean tickScheduled;

    // ── platform location ────────────────────────────────────────────────────
    @Nullable
    private Location lastLocation;
    /** {@link SystemClock#elapsedRealtime()} of the last fix; 0 = none since the process started. */
    private long lastLocationRealtime;

    // ── gnss-share ───────────────────────────────────────────────────────────
    private boolean shareReceived;
    private int shareCount = -1;
    private int shareUsedInFix = -1;
    private int shareMode = 0;
    private long shareTimestamp;

    GnssProvider(@NonNull Context context, @NonNull Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    // ── listeners ────────────────────────────────────────────────────────────

    /**
     * Declare what a listener needs, registering and unregistering the underlying feeds to match.
     * Idempotent — a brick calls this on every settings pass with the answer its switches give
     * today. {@link #NEED_NONE} removes the listener entirely.
     */
    void setNeeds(@NonNull Listener listener, int needs) {
        if (needs == NEED_NONE) {
            listeners.remove(listener);
        } else {
            listeners.put(listener, needs);
        }
        reconcile();
    }

    /** The service is going away. */
    void destroy() {
        listeners.clear();
        reconcile();
    }

    private int combinedNeeds() {
        int needs = NEED_NONE;
        for (int n : listeners.values()) {
            needs |= n;
        }
        return needs;
    }

    private void dispatch(@NonNull Dispatch what) {
        // Copy: a listener may call setNeeds from inside its callback (a brick whose content
        // switch is derived from the very data that just arrived), which mutates the map.
        List<Listener> snapshot = new ArrayList<>(listeners.keySet());
        for (Listener listener : snapshot) {
            what.deliver(listener);
        }
    }

    private interface Dispatch {
        void deliver(@NonNull Listener listener);
    }

    // ── registration ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void reconcile() {
        int needs = combinedNeeds();

        boolean wantLocation = (needs & NEED_LOCATION) != 0;
        boolean wantGnssStatus = (needs & NEED_GNSS_STATUS) != 0;
        boolean wantShare = (needs & NEED_SHARE) != 0;

        if ((wantLocation || wantGnssStatus) && locationManager == null) {
            locationManager = context.getSystemService(LocationManager.class);
        }

        if (locationManager != null) {
            if (wantLocation != locationRegistered) {
                if (wantLocation) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                            locationListener, Looper.getMainLooper());
                } else {
                    locationManager.removeUpdates(locationListener);
                    lastLocation = null;
                    lastLocationRealtime = 0;
                }
                locationRegistered = wantLocation;
            }
            if (wantGnssStatus != gnssStatusRegistered) {
                if (wantGnssStatus) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                } else {
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
                }
                gnssStatusRegistered = wantGnssStatus;
            }
        }

        if (wantShare != shareRegistered) {
            if (wantShare) {
                IntentFilter filter = new IntentFilter(SATELLITE_STATUS_ACTION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(shareReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(shareReceiver, filter);
                }
            } else {
                try {
                    context.unregisterReceiver(shareReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                handler.removeCallbacks(shareExpiryRunnable);
                clearShareData();
            }
            shareRegistered = wantShare;
        }

        // The ticker is only worth running while something can go stale on its own.
        boolean wantTick = wantLocation || wantShare;
        if (wantTick != tickScheduled) {
            if (wantTick) {
                handler.postDelayed(tickRunnable, TICK_INTERVAL_MS);
            } else {
                handler.removeCallbacks(tickRunnable);
            }
            tickScheduled = wantTick;
        }

        if (!wantLocation && !wantGnssStatus && locationManager != null
                && !locationRegistered && !gnssStatusRegistered) {
            locationManager = null;
        }
    }

    // ── platform location ────────────────────────────────────────────────────

    /** The last fix, or {@code null} if none has arrived since the feed was registered. */
    @Nullable
    Location location() {
        return lastLocation;
    }

    /**
     * Milliseconds since the last fix, or {@code -1} when there has been none. Measured on the
     * monotonic clock, so a boot-time wall-clock jump from GPS/NTP sync cannot make a fresh fix
     * look ancient.
     */
    long fixAgeMs() {
        if (lastLocationRealtime == 0) return -1;
        return SystemClock.elapsedRealtime() - lastLocationRealtime;
    }

    // ── gnss-share ───────────────────────────────────────────────────────────

    /**
     * Whether there is a gnss-share reading fresh enough to render.
     *
     * <p>The "has anything ever arrived" half is not derivable from the timestamp: it starts at 0
     * and so does the uptime clock, so during the first 30 seconds of device uptime a timestamp
     * comparison alone reads "nothing ever arrived" as fresh. Harmless for a satellite count (the
     * {@code > 0} test catches it), but a mode readout would assert a normal satellite fix on a
     * head unit where gnss-share is not even installed.
     */
    boolean shareHasData() {
        return shareReceived && SystemClock.uptimeMillis() - shareTimestamp < SHARE_TIMEOUT_MS;
    }

    /** gnss-share's satellite count, or {@code -1} when it has none to give. */
    int shareSatellites() {
        return shareCount;
    }

    /**
     * Satellites used in the fix, as reported by the same sender as {@link #shareSatellites()},
     * or {@code -1} when this sender does not report it.
     */
    int shareUsedInFix() {
        return shareUsedInFix;
    }

    boolean deadReckoning() {
        return (shareMode & MODE_DR) != 0;
    }

    boolean spoofDetected() {
        return (shareMode & MODE_SPOOF) != 0;
    }

    /**
     * Whether the map matcher says the position is anchored to a road. Tests BOTH bits, never
     * just the second: "off the road" and "no road verdict at all" arrive as different values
     * precisely so a consumer can stay silent about the second instead of asserting the first.
     */
    boolean roadAnchored() {
        return (shareMode & (MODE_ROAD_KNOWN | MODE_ON_ROAD))
                == (MODE_ROAD_KNOWN | MODE_ON_ROAD);
    }

    private void clearShareData() {
        shareReceived = false;
        shareCount = -1;
        shareUsedInFix = -1;
        shareMode = 0;
    }

    // ── platform callbacks ───────────────────────────────────────────────────

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            dispatch(Listener::onGnssTick);
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    private final Runnable shareExpiryRunnable = () -> {
        clearShareData();
        dispatch(Listener::onGnssShareStatus);
    };

    private final BroadcastReceiver shareReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            shareCount = intent.getIntExtra(EXTRA_SATELLITES_COUNT, -1);
            shareUsedInFix = intent.getIntExtra(EXTRA_USED_IN_FIX, -1);
            shareMode = intent.getIntExtra(EXTRA_MODE, 0);
            Log.d(TAG, "GNSS Share satellites count: " + shareCount
                    + ", used in fix: " + shareUsedInFix + ", mode: " + shareMode);
            shareReceived = true;
            // Monotonic clock (matching the expiry below), so a boot-time wall-clock jump from
            // GPS/NTP sync cannot prematurely expire or freeze the freshness window.
            shareTimestamp = SystemClock.uptimeMillis();
            handler.removeCallbacks(shareExpiryRunnable);
            handler.postDelayed(shareExpiryRunnable, SHARE_TIMEOUT_MS);
            dispatch(Listener::onGnssShareStatus);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            dispatch(Listener::onGnssStarted);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            dispatch(Listener::onGnssStopped);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            dispatch(listener -> listener.onGnssFirstFix(ttffMillis));
        }

    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(TAG, "Location changed: " + location);
            lastLocation = location;
            lastLocationRealtime = SystemClock.elapsedRealtime();
            dispatch(listener -> listener.onGnssLocation(location));
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
}
