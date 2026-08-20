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

    /** How often and for how long to re-check sensor support while the platform service boots.
     *  Generous window: some head units bring the eCarX platform service up minutes after our
     *  boot-autostart, and the poll itself is a single cheap binder call. */
    private static final long AVAILABILITY_POLL_INTERVAL_MS = 5_000L;
    private static final int AVAILABILITY_POLL_MAX_ATTEMPTS = 60;   // 5 min total

    /** Retry cadence for a probe that failed at the Java level (typically a cold boot where the
     *  vendor binder isn't published yet) — the SDK is retried within the same process instead
     *  of staying dead until the service restarts. */
    private static final long INIT_RETRY_DELAY_MS = 10_000L;
    private static final int MAX_INIT_ATTEMPTS_PER_PROCESS = 12;    // ~2 min of retries

    /** How long a probe may run before it is written off as hung. A vendor call that never
     *  returns would otherwise leave the crash canary armed forever, and the next launch would
     *  charge the hang to the SDK as if it had killed the process. */
    private static final long PROBE_DEADLINE_MS = 15_000L;

    /** Crash-canary bookkeeping (device-protected storage, separate file from user prefs). */
    private static final String PROBE_PREFS = "geely_car_probe";
    private static final String KEY_PROBE_IN_FLIGHT = "probeInFlight";
    private static final String KEY_CRASHED_PROBES = "crashedProbes";
    private static final String KEY_PROBE_OK = "probeOk";
    private static final String KEY_ENVIRONMENT = "environment";
    /** Unfinished probes tolerated before the integration is disabled on a firmware that has
     *  never produced a working probe — this is the Monjaro-2026 native-crash guard. */
    private static final int MAX_CRASHED_PROBES = 2;
    /** Higher tolerance once the SDK has been seen working here: unfinished markers are then
     *  most likely ordinary process deaths (swipe-away, low memory, ignition-off power cut).
     *  Still bounded, so a firmware that starts crashing after a good probe can be disabled. */
    private static final int MAX_CRASHED_PROBES_AFTER_SUCCESS = 6;

    /** IDLE = nothing started; RETRY_PENDING = a delayed retry owns the next attempt. */
    private enum InitState { IDLE, RETRY_PENDING, INITIALIZING, READY, FAILED }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);
    /** Subscriptions requested before the background init finished; applied on READY. */
    private final Map<BrickType, ValueListener> pendingSubscriptions = new EnumMap<>(BrickType.class);

    private InitState initState = InitState.IDLE;
    private int initAttempts = 0;
    @Nullable
    private ISensor sensors;

    /**
     * Sensors that have actually delivered a plausible reading. This outranks
     * {@code isSensorSupported}: some head units (seen on Atlas) answer {@code error} or
     * {@code notavailable} for a sensor that nevertheless streams correct values, so a received
     * value is treated as proof of support from then on.
     */
    private final Map<BrickType, Boolean> provenByData = new EnumMap<>(BrickType.class);

    /** Multicast: the overlay service and the settings screen both listen. */
    private final java.util.List<Runnable> availabilityListeners = new java.util.ArrayList<>(2);
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
        boolean probeOk = prefs.getBoolean(KEY_PROBE_OK, false);
        if (!environmentStamp().equals(prefs.getString(KEY_ENVIRONMENT, null))) {
            // New firmware or app build — previous verdicts no longer apply.
            prefs.edit().clear().putString(KEY_ENVIRONMENT, environmentStamp()).commit();
            probeOk = false;
        }

        int crashedProbes = prefs.getInt(KEY_CRASHED_PROBES, 0);
        if (prefs.getBoolean(KEY_PROBE_IN_FLIGHT, false)) {
            // The previous probe never finished. On a firmware where the SDK crashes natively
            // that means exactly that — but the very same marker is left behind by any other
            // way the process can die mid-probe: the user swiping the app away, the system
            // killing it under memory pressure, or the head unit cutting power on ignition-off
            // (which on these units happens constantly). Those are NOT vendor crashes, and
            // counting them is what silently disabled working Atlas installs.
            //
            // Discriminator: a probe that ever succeeded on this firmware proves the SDK is
            // healthy here, so an unfinished marker afterwards is an unrelated process death
            // and is forgiven outright.
            crashedProbes++;
            Log.w(TAG, "Previous eCarX probe did not finish (count=" + crashedProbes
                    + (probeOk ? ", SDK known good here)" : ")"));
            prefs.edit()
                    .putInt(KEY_CRASHED_PROBES, crashedProbes)
                    .putBoolean(KEY_PROBE_IN_FLIGHT, false)
                    .commit();
        }
        // A firmware where the SDK has proven itself gets a much longer leash — its unfinished
        // markers are usually ignition-off power cuts, not crashes — but the leash is finite,
        // so a firmware that starts killing the process after an update still gets disabled.
        int limit = probeOk ? MAX_CRASHED_PROBES_AFTER_SUCCESS : MAX_CRASHED_PROBES;
        if (crashedProbes >= limit) {
            Log.w(TAG, "eCarX integration disabled: " + crashedProbes
                    + " unfinished probes on this firmware. Car bricks unavailable.");
            initState = InitState.FAILED;
            return;
        }

        startProbeAttempt();
    }

    /** Runs one probe attempt on a daemon thread, under the crash canary. */
    private void startProbeAttempt() {
        initState = InitState.INITIALIZING;
        initAttempts++;
        // The marker is committed synchronously BEFORE the first vendor call; it is the
        // canary that survives a native crash.
        probePrefs().edit().putBoolean(KEY_PROBE_IN_FLIGHT, true).commit();

        // Exactly one of {probe thread, watchdog} gets to finish this attempt.
        final AtomicBoolean settled = new AtomicBoolean(false);
        final Runnable watchdog = () -> {
            if (!settled.compareAndSet(false, true)) return;
            // The probe thread is stuck inside a vendor call; it is a daemon, so we simply
            // abandon it. Disarm the canary first — a hang is not the process-killing crash
            // the canary exists to detect, and leaving it armed would disable the integration
            // on the next two launches.
            Log.w(TAG, "eCarX probe did not return within " + PROBE_DEADLINE_MS + "ms; abandoning");
            probePrefs().edit().putBoolean(KEY_PROBE_IN_FLIGHT, false).commit();
            onInitFinished(false, null);
        };
        mainHandler.postDelayed(watchdog, PROBE_DEADLINE_MS);

        Thread initThread = new Thread(() -> {
            ProbeResult probe = null;
            try {
                probe = probeVendorSdk();
            } catch (Throwable t) {
                // Java-level failure — not a native crash. Typically a cold boot where the
                // vendor binder is not published yet; retried below within this process.
                Log.w(TAG, "eCarX probe failed (non-fatal)", t);
            }
            ProbeResult finalProbe = probe;
            boolean ok = probe != null && probe.sensors != null;
            // Clearing the canary is what tells the NEXT launch the SDK didn't kill us. A
            // Java-level failure clears it too: it proves the process survived the SDK, which
            // is precisely what the canary asks.
            SharedPreferences.Editor editor = probePrefs().edit()
                    .putBoolean(KEY_PROBE_IN_FLIGHT, false);
            if (probe != null && probe.sdkFunctional) {
                // Only a demonstrably working SDK counts as a success and clears the crash
                // history. A bare non-null manager does not: at boot the SDK returns a proxy
                // that answers "error" to everything, and latching on that would permanently
                // forgive the very crashes the canary exists to catch.
                editor.putBoolean(KEY_PROBE_OK, true).putInt(KEY_CRASHED_PROBES, 0);
            }
            editor.commit();
            mainHandler.post(() -> {
                if (!settled.compareAndSet(false, true)) {
                    // The watchdog already gave up on this attempt (and possibly started the
                    // next one) — a late result must not resurrect it.
                    return;
                }
                mainHandler.removeCallbacks(watchdog);
                onInitFinished(ok, finalProbe);
            });
        }, "geely-car-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    /** What one probe attempt learned: the sensor manager, plus sensors that already hold data. */
    private static final class ProbeResult {
        @Nullable final ISensor sensors;
        final EnumMap<BrickType, Boolean> proven = new EnumMap<>(BrickType.class);
        /**
         * Whether the SDK proved it is actually functional here — some sensor answered
         * something other than {@code error}, or handed over a plausible reading. A non-null
         * sensor manager alone does NOT count: before the platform service connects, the SDK
         * hands back a live-looking proxy that answers {@code error} to everything, and
         * treating that as success is what previously disarmed the crash canary for good.
         */
        boolean sdkFunctional = false;

        ProbeResult(@Nullable ISensor sensors) {
            this.sensors = sensors;
        }
    }

    /**
     * The actual first contact with the vendor SDK. Runs on the init thread: a native crash
     * here takes the process down but leaves the canary marker set; a hang here parks a daemon
     * thread without ever blocking the UI.
     */
    @WorkerThread
    @NonNull
    private ProbeResult probeVendorSdk() {
        ISensor s = Car.create(appContext).getSensorManager();
        ProbeResult result = new ProbeResult(s);
        if (s == null) return result;
        // Touch the risky per-sensor APIs once here, under the canary's protection, rather than
        // later on the main thread.
        for (BrickType type : BrickType.values()) {
            int sensorType = sensorTypeFor(type);
            if (sensorType == 0) continue;
            try {
                FunctionStatus status = s.isSensorSupported(sensorType);
                if (status != null && status != FunctionStatus.error) {
                    // A definite answer (even "notavailable") means the SDK talked to the
                    // platform service and survived — real evidence it works on this firmware.
                    result.sdkFunctional = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "probe isSensorSupported failed for " + type, t);
            }
            // A cached reading proves the sensor works even when isSensorSupported says
            // otherwise — and it is the only evidence available for a brick the user hasn't
            // added yet (no brick, no subscription, no incoming values), which is what keeps
            // such bricks offered in the settings list on firmwares with a lying support API.
            try {
                if (isPlausibleTemperature(s.getSensorLatestValue(sensorType))) {
                    result.proven.put(type, Boolean.TRUE);
                    result.sdkFunctional = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "probe getSensorLatestValue failed for " + type, t);
            }
        }
        return result;
    }

    private void onInitFinished(boolean ok, @Nullable ProbeResult probe) {
        sensors = probe != null ? probe.sensors : null;
        if (probe != null) {
            provenByData.putAll(probe.proven);
        }
        if (ok) {
            initState = InitState.READY;
            // Apply subscriptions requested while init was still running.
            for (Map.Entry<BrickType, ValueListener> e : pendingSubscriptions.entrySet()) {
                subscribeNow(e.getKey(), e.getValue());
            }
            pendingSubscriptions.clear();
        } else if (initAttempts < MAX_INIT_ATTEMPTS_PER_PROCESS) {
            // Java-level failure, most often "vendor service not up yet" on a cold boot. Retry
            // in-process instead of giving up until the next app launch — the widget autostarts
            // at boot and may otherwise sit car-less for the whole drive. Pending subscriptions
            // are deliberately kept so a successful retry wires them up.
            //
            // RETRY_PENDING, never IDLE: the availability notification below re-enters
            // isBrickSupported synchronously, and IDLE would let ensureInitStarted fire the
            // next attempt right there — burning the whole retry budget in a single main-thread
            // turn with no delay between attempts.
            initState = InitState.RETRY_PENDING;
            mainHandler.postDelayed(() -> {
                if (initState == InitState.RETRY_PENDING) startProbeAttempt();
            }, INIT_RETRY_DELAY_MS);
        } else {
            Log.w(TAG, "eCarX sensor manager unavailable after " + initAttempts
                    + " attempts; giving up for this process");
            initState = InitState.FAILED;
            pendingSubscriptions.clear();
        }
        // Either way the answer of isBrickSupported may have changed — let the widget re-apply.
        notifyAvailabilityChanged();
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
        // Data beats metadata: a sensor that has delivered a reading is supported, whatever the
        // support API claims afterwards.
        if (Boolean.TRUE.equals(provenByData.get(type))) return true;
        FunctionStatus status = sensorSupportStatus(sensorType);
        // "notactive" still counts as supported: the sensor exists but is momentarily idle
        // (e.g. ignition state) — the brick should be offered and will update when it wakes.
        // "error" is what the SDK reports before its platform service has connected, and some
        // firmwares keep reporting it (or "notavailable") for sensors that do stream data; the
        // availability poll re-checks, and any arriving value promotes the brick via
        // provenByData above.
        boolean supported = status == FunctionStatus.active || status == FunctionStatus.notactive;
        if (!supported) {
            scheduleAvailabilityPoll();
        }
        return supported;
    }

    @Override
    public void addAvailabilityListener(@NonNull Runnable listener) {
        if (!availabilityListeners.contains(listener)) {
            availabilityListeners.add(listener);
        }
    }

    @Override
    public void removeAvailabilityListener(@NonNull Runnable listener) {
        availabilityListeners.remove(listener);
    }

    /** Notify every registered listener that {@link #isBrickSupported} may answer differently now. */
    private void notifyAvailabilityChanged() {
        // Copy: a listener may add or remove one while running (e.g. the settings screen
        // unregistering as it closes).
        for (Runnable listener : new java.util.ArrayList<>(availabilityListeners)) {
            listener.run();
        }
    }

    /**
     * Record that a sensor really delivered data and, the first time that happens, tell the
     * widget its availability answer changed — the brick may currently be hidden because
     * {@code isSensorSupported} claimed otherwise.
     */
    private void noteSensorProven(@NonNull BrickType type) {
        if (Boolean.TRUE.equals(provenByData.put(type, Boolean.TRUE))) return;
        Log.i(TAG, "Sensor for " + type + " delivered data — treating as supported");
        notifyAvailabilityChanged();
    }

    /**
     * Bounded re-check loop for the boot window where the platform service hasn't connected yet
     * and every support query returns {@code error}. Each round re-asks about every car sensor
     * that is still undecided — and also samples its cached value, which is the only evidence
     * available for a brick the user hasn't added yet (no brick, no subscription, no incoming
     * readings). Polling continues until every sensor has a verdict or the budget runs out.
     */
    private void scheduleAvailabilityPoll() {
        if (availabilityPollScheduled || availabilityPollAttempts >= AVAILABILITY_POLL_MAX_ATTEMPTS) {
            return;
        }
        availabilityPollScheduled = true;
        mainHandler.postDelayed(() -> {
            availabilityPollScheduled = false;
            availabilityPollAttempts++;
            boolean anyUndecided = false;
            boolean answerChanged = false;
            for (BrickType type : BrickType.values()) {
                int sensorType = sensorTypeFor(type);
                if (sensorType == 0) continue;
                if (Boolean.TRUE.equals(provenByData.get(type))) continue;   // already decided

                FunctionStatus status = sensorSupportStatus(sensorType);
                if (status == FunctionStatus.active || status == FunctionStatus.notactive) {
                    answerChanged = true;
                    continue;   // decided: supported
                }
                // Support API says error/notavailable. Ask the data side before believing it —
                // some firmwares report a sensor as unavailable while still serving readings.
                Float latest = latestValue(sensorType);
                if (latest != null) {
                    noteSensorProven(type);   // notifies on its own
                    continue;
                }
                if (status == FunctionStatus.notavailable) {
                    continue;   // decided: genuinely absent on this vehicle
                }
                anyUndecided = true;   // still "error" — the service hasn't answered yet
            }
            if (answerChanged) {
                notifyAvailabilityChanged();
            }
            if (anyUndecided) {
                scheduleAvailabilityPoll();
            }
        }, AVAILABILITY_POLL_INTERVAL_MS);
    }

    /** Latest cached reading, or {@code null} when unavailable / implausible. */
    @Nullable
    private Float latestValue(int sensorType) {
        ISensor s = sensors;
        if (initState != InitState.READY || s == null) return null;
        try {
            float latest = s.getSensorLatestValue(sensorType);
            return isPlausibleTemperature(latest) ? latest : null;
        } catch (Throwable t) {
            Log.w(TAG, "getSensorLatestValue failed for sensor " + sensorType, t);
            return null;
        }
    }

    @Override
    public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
        if (sensorTypeFor(type) == 0) return;
        ensureInitStarted();
        if (initState == InitState.INITIALIZING || initState == InitState.IDLE
                || initState == InitState.RETRY_PENDING) {
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
                    noteSensorProven(type);
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
                    noteSensorProven(type);
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
        availabilityListeners.clear();
        // Stop the retry / poll / watchdog chain: nothing is left to notify, and a retry that
        // fired later would re-arm the canary with no one waiting for the answer.
        mainHandler.removeCallbacksAndMessages(null);
        if (initState == InitState.RETRY_PENDING) {
            initState = InitState.IDLE;
        }
        if (initState == InitState.INITIALIZING) {
            // A probe is still running on the daemon thread. Disarm the canary: the process is
            // being torn down deliberately, and that must not read as an SDK crash next launch.
            probePrefs().edit().putBoolean(KEY_PROBE_IN_FLIGHT, false).commit();
        }
    }
}
