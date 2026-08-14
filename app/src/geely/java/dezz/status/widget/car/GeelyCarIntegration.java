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

package dezz.status.widget.car;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.BrickType;

/**
 * eCarX AdaptAPI backend for car-specific bricks: cabin ("indoor") and ambient ("outdoor")
 * temperature sensors.
 *
 * <h3>Failure containment</h3>
 * All AdaptAPI calls are wrapped in {@code catch (Throwable)}, but that is not enough on its
 * own: on some firmwares (first seen on the 2026 Monjaro refresh) the vendor SDK kills the
 * process natively during initialization — no Java catch can survive that. Two layers handle it:
 * <ul>
 *   <li><b>Background init:</b> {@code Car.create()} and the initial sensor probing run on a
 *       daemon thread, so a hanging vendor service can never ANR the UI. Until the probe
 *       finishes, {@link #isBrickSupported} reports {@code false} and subscriptions are queued.</li>
 *   <li><b>Crash canary:</b> a "probe started" marker is committed to device-protected
 *       preferences before the SDK is touched and cleared when the probe survives. If the
 *       process dies mid-probe (native crash), the next launch sees the unfinished marker;
 *       after {@value #MAX_CRASHED_PROBES} such crashes the integration is disabled for good —
 *       until the firmware fingerprint or app version changes — and the app runs like a
 *       car-less build instead of crash-looping on startup.</li>
 * </ul>
 *
 * <h3>Boot race</h3>
 * The AdaptAPI proxy connects to the {@code ecarxcar_service} binder asynchronously. Until it
 * does, {@code isSensorSupported} returns {@link FunctionStatus#error} — indistinguishable up
 * front from a genuinely unsupported vehicle. Listener registrations are queued by the SDK
 * locally, so subscriptions made early still start flowing once the service comes up, and a
 * bounded status poll re-checks support and fires the availability-changed callback when the
 * answer flips (see {@link #setAvailabilityChangedListener}).
 */
final class GeelyCarIntegration implements CarIntegration {

    private static final String TAG = "GeelyCarIntegration";

    /**
     * Sanity bounds for cabin/ambient readings — values outside are momentary CAN glitches or
     * sensor error sentinels, not real temperatures. Upper bound accommodates a sun-baked cabin.
     */
    private static final float MIN_PLAUSIBLE_TEMPERATURE_C = -40f;
    private static final float MAX_PLAUSIBLE_TEMPERATURE_C = 85f;

    /** How often and for how long to re-check sensor support while the platform service boots. */
    private static final long AVAILABILITY_POLL_INTERVAL_MS = 2_000L;
    private static final int AVAILABILITY_POLL_MAX_ATTEMPTS = 30;   // 60s total

    /** Crash-canary bookkeeping (device-protected storage, separate file from user prefs). */
    private static final String PROBE_PREFS = "geely_car_probe";
    private static final String KEY_PROBE_IN_FLIGHT = "probeInFlight";
    private static final String KEY_CRASHED_PROBES = "crashedProbes";
    private static final String KEY_PROBE_OK = "probeOk";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final int MAX_CRASHED_PROBES = 2;

    private enum InitState { IDLE, INITIALIZING, READY, FAILED }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);
    /** Subscriptions requested before the background init finished; applied on READY. */
    private final Map<BrickType, ValueListener> pendingSubscriptions = new EnumMap<>(BrickType.class);

    private InitState initState = InitState.IDLE;
    @Nullable
    private ISensor sensors;

    @Nullable
    private Runnable availabilityChangedListener;
    private int availabilityPollAttempts = 0;
    private boolean availabilityPollScheduled = false;

    /** Pairs the vendor listener with a cancellation flag so queued main-thread deliveries of an
     *  already-unsubscribed listener can be dropped instead of overwriting the placeholder. */
    private static final class Subscription {
        final ISensor.ISensorListener sensorListener;
        final AtomicBoolean cancelled;

        Subscription(ISensor.ISensorListener sensorListener, AtomicBoolean cancelled) {
            this.sensorListener = sensorListener;
            this.cancelled = cancelled;
        }
    }

    GeelyCarIntegration(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    private static int sensorTypeFor(@NonNull BrickType type) {
        switch (type) {
            case INDOOR_TEMP:
                return ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR;
            case OUTDOOR_TEMP:
                return ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT;
            default:
                return 0;
        }
    }

    private static boolean isPlausibleTemperature(float celsius) {
        // Float.MIN_VALUE is the SDK's "no data yet" sentinel (seen from getSensorLatestValue
        // before the platform service connects); it is numerically ~1.4e-45 and would otherwise
        // pass the range check and render as "0°".
        if (Float.isNaN(celsius) || celsius == Float.MIN_VALUE) return false;
        return celsius >= MIN_PLAUSIBLE_TEMPERATURE_C && celsius <= MAX_PLAUSIBLE_TEMPERATURE_C;
    }

    // ---------------------------------------------------------------------------------------
    // Guarded background initialization
    // ---------------------------------------------------------------------------------------

    private SharedPreferences probePrefs() {
        return appContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE);
    }

    /** Firmware+app identity; when it changes, crash history is reset and probing is retried. */
    private String environmentStamp() {
        return Build.FINGERPRINT + "|"
                + dezz.status.widget.VersionGetter.getAppVersionName(appContext);
    }

    /** Kick off the one-time background init. Safe to call from anywhere on the main thread. */
    private void ensureInitStarted() {
        if (initState != InitState.IDLE) return;

        SharedPreferences prefs = probePrefs();
        if (!environmentStamp().equals(prefs.getString(KEY_ENVIRONMENT, null))) {
            // New firmware or app build — previous crash verdicts no longer apply.
            prefs.edit().clear().putString(KEY_ENVIRONMENT, environmentStamp()).commit();
        }

        int crashedProbes = prefs.getInt(KEY_CRASHED_PROBES, 0);
        if (prefs.getBoolean(KEY_PROBE_IN_FLIGHT, false)) {
            // The previous probe never finished — the vendor SDK killed the process natively.
            crashedProbes++;
            prefs.edit()
                    .putInt(KEY_CRASHED_PROBES, crashedProbes)
                    .putBoolean(KEY_PROBE_IN_FLIGHT, false)
                    .commit();
            Log.w(TAG, "Previous eCarX probe crashed the process (count=" + crashedProbes + ")");
        }
        if (!prefs.getBoolean(KEY_PROBE_OK, false) && crashedProbes >= MAX_CRASHED_PROBES) {
            Log.w(TAG, "eCarX integration disabled: probe crashed " + crashedProbes
                    + " times on this firmware. Car bricks unavailable.");
            initState = InitState.FAILED;
            return;
        }

        initState = InitState.INITIALIZING;
        // The marker is committed synchronously BEFORE the first vendor call; it is the
        // canary that survives a native crash.
        prefs.edit().putBoolean(KEY_PROBE_IN_FLIGHT, true).commit();

        Thread initThread = new Thread(() -> {
            ISensor resolved = null;
            boolean survived = false;
            try {
                resolved = probeVendorSdk();
                survived = true;
            } catch (Throwable t) {
                // Java-level failure — not a crash. Clear the canary so future launches may
                // retry (e.g. a transient binder error), and degrade to "no car support".
                Log.w(TAG, "eCarX probe failed (non-fatal)", t);
            }
            probePrefs().edit()
                    .putBoolean(KEY_PROBE_IN_FLIGHT, false)
                    .putBoolean(KEY_PROBE_OK, survived && resolved != null)
                    .commit();
            ISensor finalResolved = resolved;
            boolean ok = survived && resolved != null;
            mainHandler.post(() -> onInitFinished(ok, finalResolved));
        }, "geely-car-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * The actual first contact with the vendor SDK. Runs on the init thread: a native crash
     * here takes the process down but leaves the canary marker set; a hang here parks a daemon
     * thread without ever blocking the UI.
     */
    @WorkerThread
    @Nullable
    private ISensor probeVendorSdk() {
        ISensor s = Car.create(appContext).getSensorManager();
        if (s == null) return null;
        // Touch the sensor-support API for both car sensors once, so the risky first calls all
        // happen under the canary's protection rather than later on the main thread.
        for (BrickType type : BrickType.values()) {
            int sensorType = sensorTypeFor(type);
            if (sensorType == 0) continue;
            try {
                s.isSensorSupported(sensorType);
            } catch (Throwable t) {
                Log.w(TAG, "probe isSensorSupported failed for " + type, t);
            }
        }
        return s;
    }

    private void onInitFinished(boolean ok, @Nullable ISensor resolved) {
        sensors = resolved;
        initState = ok ? InitState.READY : InitState.FAILED;
        if (ok) {
            // Apply subscriptions requested while init was still running.
            for (Map.Entry<BrickType, ValueListener> e : pendingSubscriptions.entrySet()) {
                subscribeNow(e.getKey(), e.getValue());
            }
        }
        pendingSubscriptions.clear();
        // Either way the answer of isBrickSupported may have changed — let the widget re-apply.
        if (availabilityChangedListener != null) {
            availabilityChangedListener.run();
        }
    }

    // ---------------------------------------------------------------------------------------
    // CarIntegration contract
    // ---------------------------------------------------------------------------------------

    @Nullable
    private FunctionStatus sensorSupportStatus(int sensorType) {
        if (initState != InitState.READY || sensors == null) return null;
        try {
            return sensors.isSensorSupported(sensorType);
        } catch (Throwable t) {
            Log.w(TAG, "isSensorSupported failed for sensor " + sensorType, t);
            return null;
        }
    }

    @Override
    public boolean isBrickSupported(@NonNull BrickType type) {
        int sensorType = sensorTypeFor(type);
        if (sensorType == 0) return false;
        ensureInitStarted();
        FunctionStatus status = sensorSupportStatus(sensorType);
        // "notactive" still counts as supported: the sensor exists but is momentarily idle
        // (e.g. ignition state) — the brick should be offered and will update when it wakes.
        // "error" is what the SDK reports before its platform service has connected; the
        // availability poll below re-checks and notifies once the true answer is known.
        boolean supported = status == FunctionStatus.active || status == FunctionStatus.notactive;
        if (!supported && status == FunctionStatus.error) {
            scheduleAvailabilityPoll();
        }
        return supported;
    }

    @Override
    public void setAvailabilityChangedListener(@Nullable Runnable listener) {
        availabilityChangedListener = listener;
    }

    /**
     * Bounded re-check loop for the boot window where the platform service hasn't connected yet
     * and every support query returns {@code error}. Fires the availability callback as soon as
     * any car sensor reports a definitive status, then stops.
     */
    private void scheduleAvailabilityPoll() {
        if (availabilityPollScheduled || availabilityPollAttempts >= AVAILABILITY_POLL_MAX_ATTEMPTS) {
            return;
        }
        availabilityPollScheduled = true;
        mainHandler.postDelayed(() -> {
            availabilityPollScheduled = false;
            availabilityPollAttempts++;
            boolean anyDefinitive = false;
            for (BrickType type : BrickType.values()) {
                int sensorType = sensorTypeFor(type);
                if (sensorType == 0) continue;
                FunctionStatus status = sensorSupportStatus(sensorType);
                if (status != null && status != FunctionStatus.error) {
                    anyDefinitive = true;
                    break;
                }
            }
            if (anyDefinitive) {
                availabilityPollAttempts = AVAILABILITY_POLL_MAX_ATTEMPTS; // done for good
                if (availabilityChangedListener != null) {
                    availabilityChangedListener.run();
                }
            } else {
                scheduleAvailabilityPoll();
            }
        }, AVAILABILITY_POLL_INTERVAL_MS);
    }

    @Override
    public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
        if (sensorTypeFor(type) == 0) return;
        ensureInitStarted();
        if (initState == InitState.INITIALIZING || initState == InitState.IDLE) {
            // Queue — applied in onInitFinished. Replacing any previous pending entry mirrors
            // the replace semantics of a live subscribe.
            pendingSubscriptions.put(type, listener);
            return;
        }
        if (initState == InitState.FAILED) return;
        subscribeNow(type, listener);
    }

    private void subscribeNow(@NonNull BrickType type, @NonNull ValueListener listener) {
        int sensorType = sensorTypeFor(type);
        ISensor s = sensors;
        if (s == null) return;

        Subscription previous = subscriptions.get(type);

        // The listener closes over its own cancellation flag (not a map lookup — the vendor
        // callback arrives on a binder thread and the map is main-thread-only). The gate drops
        // deliveries already queued to the main handler when unsubscribe() wins the race —
        // otherwise a stale reading would overwrite the placeholder reset and resurface when
        // the brick is re-added later.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        Subscription subscription = new Subscription(new ISensor.ISensorListener() {
            @Override
            public void onSensorEventChanged(int changedType, int value) {
            }

            @Override
            public void onSensorSupportChanged(int changedType, FunctionStatus status) {
            }

            @Override
            public void onSensorValueChanged(int changedType, float value) {
                if (changedType != sensorType || !isPlausibleTemperature(value)) return;
                // AdaptAPI delivers on a binder thread; the contract is main-thread delivery.
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    listener.onValue(type, value);
                });
            }
        }, cancelled);

        // Register the replacement BEFORE dropping the old listener: if the vendor side
        // transiently rejects the registration we keep the previous, still-working
        // subscription instead of silently freezing the brick.
        try {
            if (!s.registerListener(subscription.sensorListener, sensorType)) {
                Log.w(TAG, "registerListener rejected for " + type
                        + (previous != null ? " — keeping previous subscription" : ""));
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerListener failed for " + type
                    + (previous != null ? " — keeping previous subscription" : ""), t);
            return;
        }
        if (previous != null) {
            previous.cancelled.set(true);
            try {
                s.unregisterListener(previous.sensorListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener (replace) failed for " + type, t);
            }
        }
        subscriptions.put(type, subscription);

        // Seed with the latest cached value so the brick shows a temperature immediately
        // instead of a placeholder until the sensor's next change event (which for slow-moving
        // ambient temperature can be minutes away).
        try {
            float latest = s.getSensorLatestValue(sensorType);
            if (isPlausibleTemperature(latest)) {
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    listener.onValue(type, latest);
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSensorLatestValue failed for " + type, t);
        }
    }

    @Override
    public void unsubscribe(@NonNull BrickType type) {
        pendingSubscriptions.remove(type);
        Subscription subscription = subscriptions.remove(type);
        if (subscription == null) return;
        subscription.cancelled.set(true);
        try {
            if (sensors != null) sensors.unregisterListener(subscription.sensorListener);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterListener failed for " + type, t);
        }
    }

    @Override
    public void shutdown() {
        for (BrickType type : BrickType.values()) {
            unsubscribe(type);
        }
        availabilityChangedListener = null;
    }
}
