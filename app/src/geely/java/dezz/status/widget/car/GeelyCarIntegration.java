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
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.ICar;
import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eCarX AdaptAPI backend for car-specific bricks: cabin and ambient temperatures, fuel level and
 * range, the 12 V system voltage and the fuel tank capacity. Where each comes from, and how far
 * the SDK's support answer can be trusted for it, is described by {@link EcarxMetricSpec}.
 *
 * <h3>Failure containment</h3>
 * All AdaptAPI calls are wrapped in {@code catch (Throwable)}, but that is not enough on its
 * own: on some firmwares (first seen on the 2026 Monjaro refresh) the vendor SDK kills the
 * process natively during initialization — no Java catch can survive that. Two layers handle it:
 * <ul>
 *   <li><b>Background init:</b> {@code Car.create()} and the initial sensor probing run on a
 *       daemon thread, so a hanging vendor service can never ANR the UI. Until the probe
 *       finishes, {@link #isMetricSupported} reports {@code false} and declared needs wait.</li>
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
 * locally, so feeds registered early still start flowing once the service comes up, and a
 * bounded status poll re-checks support and fires the availability-changed callback when the
 * answer flips (see {@link #addAvailabilityListener}).
 */
final class GeelyCarIntegration implements CarIntegration {

    private static final String TAG = "GeelyCarIntegration";

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


    /**
     * How often a running feed is read directly while some listener needs it. For the 12 V
     * voltage that is the only source. For SENSOR metrics it is the safety net under the vendor
     * push feed, which re-delivers a cache that only the platform service's change events
     * refresh: when those stop, the cache repeats its last value forever. A direct read asks the
     * service each time. (On a Monjaro the fuel level froze for a whole drive, through a refill;
     * whether the direct read kept moving is what the fuel trace below is there to show.) Every
     * metric served this way moves slowly; a read costs up to three property reads (a fuel range
     * whose fuel-only source has nothing falls back to a sum of two signals).
     */
    private static final long PULL_INTERVAL_MS = 15_000L;
    /**
     * Ceiling of the pause between direct reads that keep finding no data. The vendor SDK prints
     * a stack trace for every failed read, so a feed that cannot be read is not hammered at the
     * full rate; the first good read restores {@link #PULL_INTERVAL_MS}.
     */
    private static final long PULL_INTERVAL_MAX_MS = 120_000L;

    /** Feeds whose pushed and directly read values are traced to the log, and how often at most. */
    private static final Set<CarMetric> TRACED_FEEDS =
            EnumSet.of(CarMetric.FUEL_PERCENT, CarMetric.FUEL_RANGE_KM);
    private static final long FEED_TRACE_INTERVAL_MS = 300_000L;

    /** Minimum spacing of the data samples {@link #isMetricSupported} takes for metrics whose
     *  support only data can prove — it runs on every settings pass. */
    private static final long SUPPORT_SAMPLE_INTERVAL_MS = 5_000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Vendor feeds currently running — one per metric, for the union of all needs. */
    private final Map<CarMetric, Subscription> subscriptions = new EnumMap<>(CarMetric.class);
    /**
     * What each listener declared through {@link #setNeeds}. Kept across the background init:
     * needs declared before READY are simply reconciled into vendor feeds once it is reached.
     */
    private final Map<Listener, Set<CarMetric>> needsByListener = new LinkedHashMap<>();

    private InitState initState = InitState.IDLE;
    private int initAttempts = 0;
    @Nullable
    private ISensor sensors;
    /**
     * The 12 V reader; owns its own car service binding and signal subscription. Created on the
     * first 12 V read rather than with this object, so that a vendor class it links against can
     * only ever cost the 12 V brick — this object failing to build costs every car brick.
     */
    @Nullable
    private EcarxBatteryVoltage batteryVoltage;
    private boolean batteryVoltageUnavailable;
    /**
     * The car info manager the probe obtained. Kept because a probe that runs before the platform
     * service connects gets a manager not yet wired to it — every read answers 0 — and the SDK
     * wires that same instance once the connection comes up.
     */
    @Nullable
    private ICarInfo carInfo;
    /** Fuel tank capacity from the car configuration, liters; NaN until a read succeeds. */
    private float tankLiters = Float.NaN;

    /**
     * Metrics that have actually delivered a plausible reading. This outranks
     * {@code isSensorSupported}: some head units (seen on Atlas) answer {@code error} or
     * {@code notavailable} for a sensor that nevertheless streams correct values, so a received
     * value is treated as proof of support from then on.
     */
    private final Map<CarMetric, Boolean> provenByData = new EnumMap<>(CarMetric.class);
    /** When {@link #isMetricSupported} last sampled each metric's data, elapsed-realtime ms. */
    private final long[] lastSupportSampleAt = new long[CarMetric.values().length];

    /** Metrics the availability poll last found supported by status, to notify on flips only. */
    private final Map<CarMetric, Boolean> supportedByStatus = new EnumMap<>(CarMetric.class);

    /** Multicast: the overlay service and the settings screen both listen. */
    private final List<Runnable> availabilityListeners = new ArrayList<>(2);
    private int availabilityPollAttempts = 0;
    private boolean availabilityPollScheduled = false;

    /**
     * A running feed. The cancellation flag discards main-thread deliveries still queued when the
     * feed is dropped. Each SENSOR metric gets its OWN vendor listener object:
     * {@code ISensor.unregisterListener} removes the object from every sensor type it is
     * registered on, so a listener shared between metrics could not be dropped for just one.
     */
    private static final class Subscription {
        @Nullable
        final ISensor.ISensorListener sensorListener;
        @Nullable
        final Runnable poller;
        final AtomicBoolean cancelled;

        Subscription(@Nullable ISensor.ISensorListener sensorListener, @Nullable Runnable poller,
                     @NonNull AtomicBoolean cancelled) {
            this.sensorListener = sensorListener;
            this.poller = poller;
            this.cancelled = cancelled;
        }
    }

    /**
     * The vendor push side of one SENSOR feed: the value it last changed to, written on the
     * vendor's delivery thread, and what the feed trace last logged, kept on the main thread.
     */
    private static final class PushedFeed {
        volatile float value = Float.NaN;
        volatile long changedAt;
        long tracedAt;
        float tracedPushed = Float.NaN;
        float tracedDirect = Float.NaN;
        float tracedFuelTable = Float.NaN;
    }

    /** The value each running feed last delivered to its listeners. Main thread only. */
    private final Map<CarMetric, Float> lastDispatched = new EnumMap<>(CarMetric.class);

    GeelyCarIntegration(@NonNull Context appContext) {
        this.appContext = appContext;
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

    /** What one probe attempt learned: the managers, plus metrics that already hold data. */
    private static final class ProbeResult {
        @Nullable final ISensor sensors;
        @Nullable ICarInfo carInfo;
        final EnumMap<CarMetric, Boolean> proven = new EnumMap<>(CarMetric.class);
        float tankLiters = Float.NaN;
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
        ICar car = Car.create(appContext);
        ISensor s = car.getSensorManager();
        ProbeResult result = new ProbeResult(s);
        if (s == null) return result;
        // Touch the risky per-metric APIs once here, under the canary's protection, rather than
        // later on the main thread.
        for (CarMetric metric : CarMetric.values()) {
            EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
            if (spec == null || spec.transport == EcarxMetricSpec.Transport.CAR_INFO) continue;
            if (spec.supportApiMeaningful) {
                try {
                    FunctionStatus status = s.isSensorSupported(spec.ids[0]);
                    if (status != null && status != FunctionStatus.error) {
                        // A definite answer (even "notavailable") means the SDK talked to the
                        // platform service and survived — real evidence it works on this firmware.
                        result.sdkFunctional = true;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "probe isSensorSupported failed for " + metric, t);
                }
            }
            // A cached reading proves the metric works even when the support API says otherwise
            // or says nothing — and it is the only evidence available for a brick the user
            // hasn't added yet (no brick, no feed, no incoming values), which is what keeps such
            // bricks offered in the settings list.
            if (!Float.isNaN(readLatest(metric, spec, s))) {
                result.proven.put(metric, Boolean.TRUE);
                result.sdkFunctional = true;
            }
        }
        // The tank capacity is a configuration constant. Reading it is not evidence that the
        // platform service answers, so it never sets sdkFunctional.
        try {
            result.carInfo = car.getCarInfoManager();
            float liters = readTankLiters(result.carInfo);
            if (!Float.isNaN(liters)) {
                result.tankLiters = liters;
                result.proven.put(CarMetric.FUEL_TANK_CAPACITY_L, Boolean.TRUE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "probe getCarInfoManager failed", t);
        }
        return result;
    }

    private void onInitFinished(boolean ok, @Nullable ProbeResult probe) {
        sensors = probe != null ? probe.sensors : null;
        if (probe != null) {
            provenByData.putAll(probe.proven);
            if (probe.carInfo != null) carInfo = probe.carInfo;
            if (!Float.isNaN(probe.tankLiters)) tankLiters = probe.tankLiters;
        }
        if (ok) {
            initState = InitState.READY;
            // Start feeds for the needs declared while init was still running.
            reconcile();
        } else if (initAttempts < MAX_INIT_ATTEMPTS_PER_PROCESS) {
            // Java-level failure, most often "vendor service not up yet" on a cold boot. Retry
            // in-process instead of giving up until the next app launch — the widget autostarts
            // at boot and may otherwise sit car-less for the whole drive. Declared needs are
            // kept, so a successful retry wires them up.
            //
            // RETRY_PENDING, never IDLE: the availability notification below re-enters
            // isMetricSupported synchronously, and IDLE would let ensureInitStarted fire the
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
        }
        // Either way the answer of isMetricSupported may have changed — let the widget re-apply.
        notifyAvailabilityChanged();
    }

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /**
     * The metric's current value in its {@link CarMetric} unit, or NaN when no source holds a
     * plausible one. SENSOR sources are tried in priority order. Callable from the probe thread
     * with the sensor manager it is probing, and from the main thread once READY — where the 12 V
     * voltage is read by {@link #readBatteryVoltage} instead, which binds services of its own.
     */
    private float readLatest(@NonNull CarMetric metric, @NonNull EcarxMetricSpec spec,
                             @Nullable ISensor s) {
        switch (spec.transport) {
            case SENSOR:
                if (s == null || spec.decoder == null) return Float.NaN;
                for (int sensorType : spec.ids) {
                    try {
                        float value = spec.decoder.decode(s.getSensorLatestValue(sensorType));
                        if (!Float.isNaN(value)) return value;
                    } catch (Throwable t) {
                        Log.w(TAG, "getSensorLatestValue failed for " + metric + " / " + sensorType, t);
                    }
                }
                return Float.NaN;
            case SIGNAL:
                return EcarxBatteryVoltage.readSignalOnce(s);
            case CAR_INFO:
                return tankLiters;
            default:
                return Float.NaN;
        }
    }

    /**
     * {@link #readLatest(CarMetric, EcarxMetricSpec, ISensor)} with the live managers.
     * Main thread only. A tank capacity the probe could not read is re-read here until it arrives.
     */
    private float readLatest(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null || initState != InitState.READY) return Float.NaN;
        if (spec.transport == EcarxMetricSpec.Transport.CAR_INFO && Float.isNaN(tankLiters)) {
            try {
                tankLiters = readTankLiters(carInfo);
            } catch (Throwable t) {
                Log.w(TAG, "getCarInfoFloat failed for the fuel tank capacity", t);
            }
            // A constant has no feed to deliver it: whoever already declared it gets it now, by
            // whichever caller happened to read it first.
            Subscription waiting = subscriptions.get(metric);
            if (!Float.isNaN(tankLiters) && waiting != null) {
                postLatest(metric, waiting.cancelled);
            }
        }
        if (spec.transport == EcarxMetricSpec.Transport.SIGNAL) {
            return readBatteryVoltage();
        }
        return readLatest(metric, spec, sensors);
    }

    private float readBatteryVoltage() {
        if (batteryVoltageUnavailable) return Float.NaN;
        try {
            if (batteryVoltage == null) batteryVoltage = new EcarxBatteryVoltage(appContext);
            return batteryVoltage.read(sensors);
        } catch (Throwable t) {
            // A linkage error from vendor classes will not heal within this process.
            Log.w(TAG, "12 V reader unavailable", t);
            batteryVoltageUnavailable = true;
            return Float.NaN;
        }
    }

    /** The configured tank capacity in liters, or NaN when the manager cannot answer yet. */
    private static float readTankLiters(@Nullable ICarInfo info) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(CarMetric.FUEL_TANK_CAPACITY_L);
        if (info == null || spec == null || spec.decoder == null) return Float.NaN;
        return spec.decoder.decode(info.getCarInfoFloat(spec.ids[0]));
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
    public boolean isMetricSupported(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null) return false;
        ensureInitStarted();
        // Data beats metadata: a metric that has delivered a reading is supported, whatever the
        // support API claims afterwards.
        if (Boolean.TRUE.equals(provenByData.get(metric))) return true;
        if (!spec.supportApiMeaningful) {
            // Only data can decide. Sample it here rather than wait for the bounded poll, so a
            // car that starts reporting after the poll budget ran out still gets its bricks.
            if (sampleForSupport(metric)) return true;
            scheduleAvailabilityPoll();
            return false;
        }
        FunctionStatus status = sensorSupportStatus(spec.ids[0]);
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

    /**
     * Throttled data sample for a metric whose support only data proves. On success the metric is
     * recorded as proven; the availability notification is POSTED, because this runs inside a
     * settings pass and a synchronous notification would start another one within it.
     */
    private boolean sampleForSupport(@NonNull CarMetric metric) {
        if (initState != InitState.READY) return false;
        long now = SystemClock.elapsedRealtime();
        int slot = metric.ordinal();
        if (lastSupportSampleAt[slot] != 0 && now - lastSupportSampleAt[slot] < SUPPORT_SAMPLE_INTERVAL_MS) {
            return false;
        }
        lastSupportSampleAt[slot] = now;
        if (Float.isNaN(readLatest(metric))) return false;
        provenByData.put(metric, Boolean.TRUE);
        Log.i(TAG, "Metric " + metric + " holds data — treating as supported");
        mainHandler.post(this::notifyAvailabilityChanged);
        return true;
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

    /** Notify every registered listener that {@link #isMetricSupported} may answer differently now. */
    private void notifyAvailabilityChanged() {
        // Copy: a listener may add or remove one while running (e.g. the settings screen
        // unregistering as it closes).
        for (Runnable listener : new ArrayList<>(availabilityListeners)) {
            listener.run();
        }
    }

    /**
     * Record that a metric really delivered data and, the first time that happens, tell the
     * widget its availability answer changed — the brick may currently be hidden because
     * the support API claimed otherwise, or because nothing had been read yet.
     */
    private void noteMetricProven(@NonNull CarMetric metric) {
        if (Boolean.TRUE.equals(provenByData.put(metric, Boolean.TRUE))) return;
        Log.i(TAG, "Metric " + metric + " delivered data — treating as supported");
        notifyAvailabilityChanged();
    }

    /**
     * Bounded re-check loop for the boot window where the platform service hasn't connected yet
     * and every support query returns {@code error}. Each round re-asks about every metric that
     * is still undecided — and also samples its cached value, which is the only evidence
     * available for a brick the user hasn't added yet (no brick, no feed, no incoming
     * readings). Polling continues until every metric has a verdict or the budget runs out.
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
            for (CarMetric metric : CarMetric.values()) {
                EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
                if (spec == null) continue;
                if (Boolean.TRUE.equals(provenByData.get(metric))) continue;   // already decided

                FunctionStatus status = spec.supportApiMeaningful
                        ? sensorSupportStatus(spec.ids[0]) : null;
                if (status == FunctionStatus.active || status == FunctionStatus.notactive) {
                    // Decided: supported. Notify only when the verdict flips — a metric that
                    // stays supported by status would otherwise re-run the whole settings pass on
                    // every round for as long as some other metric keeps the poll alive.
                    if (!Boolean.TRUE.equals(supportedByStatus.put(metric, Boolean.TRUE))) {
                        answerChanged = true;
                    }
                    continue;
                }
                supportedByStatus.remove(metric);
                // Support API says error/notavailable, or has nothing to say. Ask the data side
                // before believing it — some firmwares report a sensor as unavailable while
                // still serving readings.
                if (!Float.isNaN(readLatest(metric))) {
                    noteMetricProven(metric);   // notifies on its own
                    continue;
                }
                if (status == FunctionStatus.notavailable) {
                    continue;   // decided: genuinely absent on this vehicle
                }
                anyUndecided = true;   // no answer yet — the service or the car hasn't reported
            }
            if (answerChanged) {
                notifyAvailabilityChanged();
            }
            if (anyUndecided) {
                scheduleAvailabilityPoll();
            }
        }, AVAILABILITY_POLL_INTERVAL_MS);
    }

    @Override
    public void setNeeds(@NonNull Listener listener, @NonNull Set<CarMetric> needs) {
        Set<CarMetric> previous = needsByListener.get(listener);
        Set<CarMetric> added = EnumSet.noneOf(CarMetric.class);
        for (CarMetric metric : needs) {
            if (EcarxMetricSpec.of(metric) != null && (previous == null || !previous.contains(metric))) {
                added.add(metric);
            }
        }
        if (needs.isEmpty()) {
            needsByListener.remove(listener);
        } else {
            needsByListener.put(listener, EnumSet.copyOf(needs));
            ensureInitStarted();
        }
        Set<CarMetric> started = reconcile();
        // A feed that was already running seeded its listeners when it started; a listener that
        // joins it later needs its own seed, or it sits on the placeholder until the next change
        // event — minutes away for an ambient temperature, forever for the tank capacity.
        for (CarMetric metric : added) {
            Subscription running = subscriptions.get(metric);
            if (running != null && !started.contains(metric)) {
                seed(metric, running.cancelled, listener);
            }
        }
    }

    /**
     * Bring the running vendor feeds in line with the union of all declared needs. A no-op
     * until the background init is READY; {@link #onInitFinished} calls it again then.
     *
     * @return the metrics whose feed this call started — each already seeded to its listeners
     */
    @NonNull
    private Set<CarMetric> reconcile() {
        Set<CarMetric> started = EnumSet.noneOf(CarMetric.class);
        if (initState != InitState.READY) return started;
        Set<CarMetric> wanted = EnumSet.noneOf(CarMetric.class);
        for (Set<CarMetric> needs : needsByListener.values()) {
            wanted.addAll(needs);
        }
        for (CarMetric metric : CarMetric.values()) {
            boolean running = subscriptions.containsKey(metric);
            if (wanted.contains(metric) && !running) {
                if (subscribeNow(metric)) started.add(metric);
            } else if (!wanted.contains(metric) && running) {
                unsubscribeNow(metric);
            }
        }
        return started;
    }

    /** Deliver a reading to every listener that still needs the metric at delivery time. */
    private void dispatch(@NonNull CarMetric metric, float value) {
        lastDispatched.put(metric, value);
        // Copy: a listener may redeclare its needs from inside the callback.
        for (Map.Entry<Listener, Set<CarMetric>> e : new ArrayList<>(needsByListener.entrySet())) {
            if (e.getValue().contains(metric)) {
                e.getKey().onCarValue(metric, value);
            }
        }
    }

    /** Post the metric's current value to all its listeners, if it has one. */
    private void postLatest(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled) {
        float latest = readLatest(metric);
        if (Float.isNaN(latest)) return;
        mainHandler.post(() -> {
            if (cancelled.get()) return;
            noteMetricProven(metric);
            dispatch(metric, latest);
        });
    }

    /** Post the metric's current value to one late-joining listener, if it has one. */
    private void seed(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled,
                      @NonNull Listener listener) {
        float read = readLatest(metric);
        // A read that finds nothing right now must not leave the newcomer waiting for the next
        // change: the feed only delivers changes, and a parked car may not produce one for hours.
        Float delivered = lastDispatched.get(metric);
        float latest = !Float.isNaN(read) ? read : delivered != null ? delivered : Float.NaN;
        if (Float.isNaN(latest)) return;
        mainHandler.post(() -> {
            if (cancelled.get()) return;
            Set<CarMetric> needs = needsByListener.get(listener);
            if (needs == null || !needs.contains(metric)) return;
            noteMetricProven(metric);
            listener.onCarValue(metric, latest);
        });
    }

    /** Start the vendor feed for one metric. Returns whether it is running now. */
    private boolean subscribeNow(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null) return false;
        switch (spec.transport) {
            case SENSOR:
                return subscribeSensor(metric, spec);
            case SIGNAL:
                return subscribeSignal(metric);
            case CAR_INFO: {
                // A constant: nothing to register. Hand over what is known; if nothing is yet
                // (a probe that ran before the platform connected), the availability poll keeps
                // re-reading, and the first successful read delivers it (see readLatest).
                AtomicBoolean cancelled = new AtomicBoolean(false);
                subscriptions.put(metric, new Subscription(null, null, cancelled));
                if (!Float.isNaN(tankLiters)) {
                    postLatest(metric, cancelled);
                } else if (Float.isNaN(readLatest(metric))) {
                    // Still unknown; a successful read inside readLatest already delivered it.
                    scheduleAvailabilityPoll();
                }
                return true;
            }
            default:
                return false;
        }
    }

    private boolean subscribeSensor(@NonNull CarMetric metric, @NonNull EcarxMetricSpec spec) {
        ISensor s = sensors;
        if (s == null || spec.decoder == null) return false;
        // Pushes are taken from the preferred source only. A fallback source is consulted by the
        // direct reads, which apply the priority in one place (readLatest) — two places deciding
        // which source wins would flip the reading between them.
        final int pushedSource = spec.ids[0];
        final PushedFeed pushed = new PushedFeed();

        // The listener closes over its own cancellation flag (not a map lookup — the vendor
        // callback arrives on a binder thread and the map is main-thread-only). The gate drops
        // deliveries already queued to the main handler when the feed is dropped; a listener
        // that merely stopped needing the metric is filtered again at delivery time in dispatch.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        ISensor.ISensorListener sensorListener = new ISensor.ISensorListener() {
            @Override
            public void onSensorEventChanged(int changedType, int value) {
            }

            @Override
            public void onSensorSupportChanged(int changedType, FunctionStatus status) {
            }

            @Override
            public void onSensorValueChanged(int changedType, float raw) {
                if (changedType != pushedSource) return;
                float value = spec.decoder.decode(raw);
                if (Float.isNaN(value)) return;
                // The SDK re-delivers its cached value every 200 ms whether or not it changed, so
                // only a change is news. Re-delivering the unchanged cache would also overwrite a
                // fresher direct read with whatever the cache held when events last arrived.
                boolean first = Float.isNaN(pushed.value);
                if (Float.compare(value, pushed.value) == 0) return;
                pushed.value = value;
                pushed.changedAt = SystemClock.elapsedRealtime();
                // AdaptAPI delivers on a binder thread; the contract is main-thread delivery.
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    noteMetricProven(metric);
                    // The first delivery is the cache as the SDK holds it, which may be stale;
                    // once a direct read has spoken, only later changes of the cache are news.
                    if (first && lastDispatched.containsKey(metric)) return;
                    dispatch(metric, value);
                });
            }
        };

        // A registration the vendor rejects leaves the pushes silent — some firmwares reject
        // sensors they still serve — so the direct reads below run either way.
        try {
            if (!s.registerListener(sensorListener, pushedSource)) {
                Log.w(TAG, "registerListener rejected for " + metric + " / " + pushedSource);
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerListener failed for " + metric + " / " + pushedSource, t);
        }
        subscriptions.put(metric,
                new Subscription(sensorListener, startPuller(metric, cancelled, pushed), cancelled));
        return true;
    }

    private boolean subscribeSignal(@NonNull CarMetric metric) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        subscriptions.put(metric, new Subscription(null, startPuller(metric, cancelled, null), cancelled));
        return true;
    }

    /**
     * Read a running feed directly and deliver it when it differs from what the feed last
     * delivered: every {@link #PULL_INTERVAL_MS}, backing off up to {@link #PULL_INTERVAL_MAX_MS}
     * while the reads find nothing. The first run doubles as the seed, so a freshly shown brick
     * does not wait for a change event; it is posted rather than run inline because reconcile
     * may be running inside a settings pass.
     *
     * @param pushed the vendor push side of a SENSOR feed, traced next to the direct reads; null
     *               for a feed that is only ever read directly
     */
    @NonNull
    private Runnable startPuller(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled,
                                 @Nullable PushedFeed pushed) {
        Runnable puller = new Runnable() {
            private long interval = PULL_INTERVAL_MS;

            @Override
            public void run() {
                if (cancelled.get()) return;
                float value = readLatest(metric);
                if (Float.isNaN(value)) {
                    interval = Math.min(interval * 2, PULL_INTERVAL_MAX_MS);
                } else {
                    interval = PULL_INTERVAL_MS;
                    noteMetricProven(metric);
                    Float last = lastDispatched.get(metric);
                    if (last == null || Float.compare(last, value) != 0) dispatch(metric, value);
                }
                if (pushed != null) traceFeed(metric, pushed, value);
                mainHandler.postDelayed(this, interval);
            }
        };
        mainHandler.post(puller);
        return puller;
    }

    /**
     * Log what a fuel feed's pushes and its direct read say — and for the fuel level, also the
     * second fuel signal (the DIM fuel-table level) — when any of them moved since the last line.
     * Checked at most every {@link #FEED_TRACE_INTERVAL_MS}. The push drifting away from the
     * direct read is a stuck push feed; push and direct standing still while the fuel table moves
     * is a stuck percentage signal, which a switch to the fuel table would cure.
     */
    private void traceFeed(@NonNull CarMetric metric, @NonNull PushedFeed pushed, float direct) {
        if (!TRACED_FEEDS.contains(metric)) return;
        long now = SystemClock.elapsedRealtime();
        if (pushed.tracedAt != 0 && now - pushed.tracedAt < FEED_TRACE_INTERVAL_MS) return;
        pushed.tracedAt = now;
        float pushedValue = pushed.value;
        float fuelTable = metric == CarMetric.FUEL_PERCENT ? fuelTableLiters() : Float.NaN;
        if (Float.compare(pushedValue, pushed.tracedPushed) == 0
                && Float.compare(direct, pushed.tracedDirect) == 0
                && Float.compare(fuelTable, pushed.tracedFuelTable) == 0) {
            return;
        }
        pushed.tracedPushed = pushedValue;
        pushed.tracedDirect = direct;
        pushed.tracedFuelTable = fuelTable;
        StringBuilder line = new StringBuilder("feed ").append(metric)
                .append(": pushed ").append(pushedValue);
        long changedAt = pushed.changedAt;
        if (changedAt != 0) {
            line.append(" (changed ").append((now - changedAt) / 1000).append(" s ago)");
        }
        line.append(", direct ").append(direct);
        if (metric == CarMetric.FUEL_PERCENT) {
            line.append(", fuel table ").append(fuelTable).append(" L");
        }
        Log.i(TAG, line.toString());
    }

    /** The DIM fuel-table level in liters, for the trace only; NaN when it has no data. */
    private float fuelTableLiters() {
        ISensor s = sensors;
        if (s == null) return Float.NaN;
        try {
            return EcarxReadings.fuelTableLiters(s.getSensorLatestValue(ISensor.SENSOR_TYPE_FUEL_LEVEL));
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    private void unsubscribeNow(@NonNull CarMetric metric) {
        Subscription subscription = subscriptions.remove(metric);
        if (subscription == null) return;
        // A feed started again later must seed its listeners, not judge its first read unchanged.
        lastDispatched.remove(metric);
        subscription.cancelled.set(true);
        if (subscription.poller != null) {
            mainHandler.removeCallbacks(subscription.poller);
        }
        if (subscription.sensorListener != null) {
            try {
                if (sensors != null) sensors.unregisterListener(subscription.sensorListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener failed for " + metric, t);
            }
        }
    }

    @Override
    public void shutdown() {
        needsByListener.clear();
        for (CarMetric metric : CarMetric.values()) {
            unsubscribeNow(metric);
        }
        if (batteryVoltage != null) {
            try {
                batteryVoltage.release();
            } catch (Throwable t) {
                Log.w(TAG, "12 V reader release failed", t);
            }
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
