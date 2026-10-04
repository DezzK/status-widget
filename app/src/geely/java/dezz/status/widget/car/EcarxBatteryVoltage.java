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

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.AbsCarSignal;
import com.ecarx.xui.adaptapi.AdaptAPI;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.ECarXCarPropertyValue;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.signal.SignalFilter;

/**
 * The 12 V system voltage on eCarX head units, from whichever of two sources answers first:
 * <ol>
 *   <li><b>VHAL vendor property {@code VPOWERINFO_BATTERY_VOL} (0x287000E4)</b>, read through
 *       Android's car service. It is the head unit's own measurement of its supply, so it needs
 *       nothing from the CAN bus; a Tugella VHAL dump lists it as readable, and it is what the
 *       DriveHome project reads on Geely, Livan, Belgee and Knewstar units.</li>
 *   <li><b>CAN signal {@code VehBattUSysU} (30731)</b> with its quality flag, through eCarX's
 *       {@code car_signal} service. Nothing in the SDK reads or subscribes to it, so it is
 *       read directly, and also subscribed here explicitly: a service that only holds values of
 *       subscribed signals would otherwise answer 0 forever. A pushed value is used only while the
 *       direct read has none.</li>
 * </ol>
 *
 * <p>Why two, and why the long diagnosis: on real cars the vendor classes on the boot class path
 * take precedence over the copies bundled in this APK, so which implementation answers — and
 * whether it exposes {@link AbsCarSignal} at all — depends on the firmware. Every failure here is
 * otherwise silent (the brick is simply never offered), so each change in the outcome of a source
 * is logged once, with the raw values, under the integration's tag.
 *
 * <p>Main thread only, except {@link #readSignalOnce}.
 */
final class EcarxBatteryVoltage {

    private static final String TAG = "GeelyCarIntegration";

    /** VPOWERINFO_BATTERY_VOL: vendor group, byte[], global area. */
    static final int VHAL_BATTERY_VOLTAGE = 0x287000E4;
    private static final int VHAL_GLOBAL_AREA = 0;

    private static final int SIGNAL_VALUE = CarSignalManager.SignalId_VehBattUSysU;
    private static final int SIGNAL_QUALITY = CarSignalManager.SignalId_VehBattUSysUQf;

    /**
     * Pause before retrying a car-service step that failed: asking a connected service for its
     * property manager, or a bind — which waits four times longer after each further failure.
     */
    private static final long CAR_SERVICE_RETRY_MS = 30_000L;
    /** Binds attempted before the car service is written off for this process. */
    private static final int MAX_FAILED_BINDS = 4;

    /** Marks a signal no event has delivered yet. */
    private static final int NO_EVENT = Integer.MIN_VALUE;

    private final Context appContext;

    // ── VHAL through Android's car service ───────────────────────────────────

    /**
     * The current binding attempt. Callbacks from any other (older) attempt are ignored: the
     * car-lib keeps an abandoned {@code Car}'s connection alive and may still call it.
     */
    @Nullable
    private Binding binding;
    @Nullable
    private CarPropertyManager propertyManager;
    private int failedBinds;
    /** Elapsed-realtime before which no new bind is attempted. */
    private long bindRetryAt;
    /** Elapsed-realtime before which a missing property manager is not asked for again. */
    private long managerRetryAt;
    @NonNull
    private String carServiceState = "not bound yet";

    /** One {@code Car} and the connection callbacks that belong to it. */
    private final class Binding implements ServiceConnection {
        @Nullable
        android.car.Car car;
        boolean everConnected;
        /** The service died after connecting; the car-lib will reconnect this Car by itself. */
        boolean lost;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (binding != this || car == null) return;
            if (lost) {
                // The car-lib reconnected after a service death but kept the managers bound to the
                // dead process, and its own disconnect() skipped the unbind back then. Now that
                // it counts as connected again, disconnect() really tears down and unbinds; start
                // over with a fresh Car.
                binding = null;
                disconnectQuietly(car);
                carServiceState = "service restarted, rebinding";
                ensureCarService();
                return;
            }
            everConnected = true;
            failedBinds = 0;
            acquirePropertyManager(car);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (binding != this) return;
            propertyManager = null;
            if (everConnected) {
                lost = true;
                carServiceState = "service lost, waiting for it";
                return;
            }
            // Never connected: the car-lib gave up after its own bind retries. Nothing is bound.
            noteBindFailed("bind failed");
        }
    }

    // ── CAN signal through eCarX's car_signal service ────────────────────────

    /** The manager our callback is registered with; a new instance means a reconnected service. */
    @Nullable
    private CarSignalManager subscribedManager;
    /** The manager that rejected the subscription — not retried until the manager changes. */
    @Nullable
    private CarSignalManager rejectedManager;
    private volatile int eventValue = NO_EVENT;
    private volatile int eventQuality = NO_EVENT;
    /** The last error the service pushed for our signals, for the diagnosis. */
    @Nullable
    private volatile String signalError;

    /**
     * Created on first subscription, not with this object: it implements a vendor interface, and
     * this object is built together with the whole car integration — a vendor class that fails
     * to link here must cost the 12 V brick, not every car brick.
     */
    @Nullable
    private CarSignalManager.CarSignalEventCallback signalCallback;

    @NonNull
    private CarSignalManager.CarSignalEventCallback signalCallback() {
        if (signalCallback == null) {
            signalCallback = new CarSignalManager.CarSignalEventCallback() {
                @Override
                public void onChangeEvent(ECarXCarPropertyValue value) {
                    if (value == null) return;
                    Object payload = value.getValue();
                    if (!(payload instanceof Integer)) return;
                    if (value.getPropertyId() == SIGNAL_VALUE) {
                        eventValue = (Integer) payload;
                    } else if (value.getPropertyId() == SIGNAL_QUALITY) {
                        eventQuality = (Integer) payload;
                    }
                }

                @Override
                public void onErrorEvent(int propertyId, int zone) {
                    // Whatever was pushed before no longer holds.
                    if (propertyId == SIGNAL_VALUE) {
                        eventValue = NO_EVENT;
                    } else if (propertyId == SIGNAL_QUALITY) {
                        eventQuality = NO_EVENT;
                    } else {
                        return;
                    }
                    signalError = "error event for " + propertyId + " (zone " + zone + ")";
                }
            };
        }
        return signalCallback;
    }

    // ── diagnosis ────────────────────────────────────────────────────────────

    /** Outcome categories of the last read; a line is logged whenever they change. */
    @Nullable
    private String lastOutcome;

    EcarxBatteryVoltage(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    /** The current voltage in volts, or NaN when neither source has a plausible one. */
    @MainThread
    float read(@Nullable ISensor sensors) {
        Report report = new Report();
        float volts = readVhal(report);
        if (Float.isNaN(volts)) {
            volts = readSignalValue(sensors, report, this);
        }
        String outcome = report.outcome.toString();
        if (!outcome.equals(lastOutcome)) {
            lastOutcome = outcome;
            Log.i(TAG, "12 V: " + report.detail + " | " + environment(sensors));
        }
        return volts;
    }

    /**
     * One direct read of the CAN signal, for the init probe: no subscription, no binding, no
     * diagnosis. Safe on any thread.
     */
    static float readSignalOnce(@Nullable ISensor sensors) {
        return readSignalValue(sensors, null, null);
    }

    /** Drop the subscription and the car service binding. The instance may be used again. */
    @MainThread
    void release() {
        CarSignalManager manager = subscribedManager;
        subscribedManager = null;
        rejectedManager = null;
        if (manager != null) unregisterQuietly(manager);
        Binding current = binding;
        binding = null;
        propertyManager = null;
        failedBinds = 0;
        bindRetryAt = 0;
        carServiceState = "released";
        if (current != null && current.car != null) disconnectQuietly(current.car);
    }

    // ── VHAL ─────────────────────────────────────────────────────────────────

    private float readVhal(@NonNull Report report) {
        CarPropertyManager manager = propertyManager;
        if (manager == null) {
            Binding current = binding;
            if (current == null) {
                ensureCarService();
            } else if (current.everConnected && !current.lost && current.car != null
                    && SystemClock.elapsedRealtime() >= managerRetryAt) {
                acquirePropertyManager(current.car);
            }
            manager = propertyManager;
        }
        if (manager == null) {
            // The kind stays the same across one failure mode, however often it is re-read, so a
            // car service that keeps failing does not log a line per poll.
            String kind = failedBinds > 0 ? "bind failed" : carServiceState;
            report.add("vhal", kind, "vhal " + carServiceState);
            return Float.NaN;
        }
        try {
            CarPropertyValue<byte[]> value =
                    manager.getProperty(byte[].class, VHAL_BATTERY_VOLTAGE, VHAL_GLOBAL_AREA);
            byte[] bytes = value != null ? value.getValue() : null;
            float volts = EcarxReadings.batteryVoltage(EcarxReadings.littleEndian(bytes),
                    EcarxReadings.SIGNAL_ABSENT);
            report.add("vhal", verdict(volts, bytes == null),
                    "vhal [" + hex(bytes) + "] status="
                            + (value != null ? value.getStatus() : "null") + " -> " + volts);
            return volts;
        } catch (Throwable t) {
            report.add("vhal", "threw " + t.getClass().getSimpleName(), "vhal threw " + t);
            return Float.NaN;
        }
    }

    /** Start one bind to Android's car service unless one is running or a retry is not due. */
    private void ensureCarService() {
        if (binding != null) return;
        if (SystemClock.elapsedRealtime() < bindRetryAt) return;
        Binding attempt = new Binding();
        try {
            attempt.car = android.car.Car.createCar(appContext, attempt);
            if (attempt.car == null) {
                // Returned when the device does not declare the automotive feature: no car
                // service will ever be there.
                carServiceState = "no car service (createCar returned null)";
                bindRetryAt = Long.MAX_VALUE;
                return;
            }
            binding = attempt;
            carServiceState = "binding";
            attempt.car.connect();
        } catch (Throwable t) {
            noteBindFailed("bind threw " + t);
        }
    }

    /** Count a failed bind and schedule the next one, or give up after {@link #MAX_FAILED_BINDS}. */
    private void noteBindFailed(@NonNull String what) {
        binding = null;
        failedBinds++;
        boolean givingUp = failedBinds >= MAX_FAILED_BINDS;
        bindRetryAt = givingUp ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() + (CAR_SERVICE_RETRY_MS << (2 * (failedBinds - 1)));
        carServiceState = what + " (" + failedBinds + (givingUp ? ", giving up)" : ", retrying)");
    }

    private void acquirePropertyManager(@NonNull android.car.Car car) {
        try {
            // The Android 9 property manager lists the properties this app may read as it is
            // built; a missing permission can surface right here.
            propertyManager = (CarPropertyManager) car.getCarManager(android.car.Car.PROPERTY_SERVICE);
            carServiceState = propertyManager != null ? "connected" : "no property manager";
        } catch (Throwable t) {
            propertyManager = null;
            carServiceState = "no property manager: " + t;
        }
        if (propertyManager == null) {
            managerRetryAt = SystemClock.elapsedRealtime() + CAR_SERVICE_RETRY_MS;
        }
    }

    private static void disconnectQuietly(@NonNull android.car.Car car) {
        try {
            car.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "12 V: car service disconnect failed", t);
        }
    }

    // ── CAN signal ───────────────────────────────────────────────────────────

    /**
     * The CAN-signal read shared by the probe (no owner: direct read only) and the main-thread
     * reader (owner: also subscribes, and falls back to pushed values when the direct read has
     * none).
     */
    private static float readSignalValue(@Nullable ISensor sensors, @Nullable Report report,
                                         @Nullable EcarxBatteryVoltage owner) {
        if (!(sensors instanceof AbsCarSignal)) {
            if (report != null) {
                report.add("signal", "no AbsCarSignal", "signal: sensor manager is "
                        + (sensors == null ? "null" : sensors.getClass().getName()));
            }
            return Float.NaN;
        }
        CarSignalManager signalManager;
        try {
            // Not AbsCarSignal.getSignalValue: that goes through a cache which only the SDK's own
            // signal subscriptions refresh, so the first value it read — even the "not
            // connected" default — would be returned forever. The manager reads afresh.
            ECarXCar platform = ((AbsCarSignal) sensors).getECarXCar();
            if (platform == null) {
                if (report != null) report.add("signal", "not connected", "signal: not connected");
                return Float.NaN;
            }
            Object manager = platform.getCarManager(ECarXCar.SIGNAL_SERVICE);
            if (!(manager instanceof CarSignalManager)) {
                if (report != null) {
                    report.add("signal", "no manager", "signal: manager is " + manager);
                }
                return Float.NaN;
            }
            signalManager = (CarSignalManager) manager;
        } catch (Throwable t) {
            signalFailed(report, t, "car_signal manager unavailable");
            return Float.NaN;
        }
        boolean subscribed = owner != null && owner.subscribe(signalManager, report);

        int raw;
        int quality;
        try {
            raw = signalManager.getSignalValue(SIGNAL_VALUE);
            quality = readQuality(signalManager);
        } catch (Throwable t) {
            // The service is in trouble; anything it pushed earlier is not to be trusted either.
            if (owner != null) {
                owner.eventValue = NO_EVENT;
                owner.eventQuality = NO_EVENT;
            }
            signalFailed(report, t, "getSignalValue failed");
            return Float.NaN;
        }
        float volts = EcarxReadings.batteryVoltage(raw, quality);
        String from = "pull";
        if (Float.isNaN(volts) && subscribed && owner.eventValue != NO_EVENT) {
            int pushedQuality = owner.eventQuality != NO_EVENT ? owner.eventQuality : quality;
            float pushed = EcarxReadings.batteryVoltage(owner.eventValue, pushedQuality);
            if (!Float.isNaN(pushed)) {
                volts = pushed;
                raw = owner.eventValue;
                quality = pushedQuality;
                from = "event";
            }
        }
        if (report != null) {
            String error = owner != null ? owner.signalError : null;
            report.add("signal",
                    from + " " + verdict(volts, raw <= 0) + (error != null ? " +error" : ""),
                    "signal " + from + " raw=" + raw + " qf=" + quality + " -> " + volts
                            + (subscribed ? ", subscribed" : "")
                            + (error != null ? ", last " + error : ""));
        }
        return volts;
    }

    /** A CAN-signal failure: reported to the diagnosis when there is one, logged otherwise. */
    private static void signalFailed(@Nullable Report report, @NonNull Throwable t,
                                     @NonNull String what) {
        if (report != null) {
            report.add("signal", "threw " + t.getClass().getSimpleName(), "signal threw " + t);
        } else {
            Log.w(TAG, "12 V: " + what, t);
        }
    }

    /** A firmware without the flag signal may throw rather than answer 0; that is "absent". */
    private static int readQuality(@NonNull CarSignalManager signalManager) {
        try {
            return signalManager.getSignalValue(SIGNAL_QUALITY);
        } catch (Throwable t) {
            return EcarxReadings.SIGNAL_ABSENT;
        }
    }

    /**
     * Register for the voltage signals on this manager instance. A reconnected service hands out
     * a new manager with its callbacks cleared, so the registration follows the instance.
     *
     * @return whether a subscription is active on {@code signalManager}
     */
    private boolean subscribe(@NonNull CarSignalManager signalManager, @Nullable Report report) {
        if (subscribedManager == signalManager) return true;
        if (rejectedManager == signalManager) return false;
        // Cleared before registering: the service may push the current values straight away,
        // on its own event thread, while registerCallback is still returning.
        eventValue = NO_EVENT;
        eventQuality = NO_EVENT;
        signalError = null;
        try {
            SignalFilter filter = new SignalFilter();
            filter.add(SIGNAL_VALUE);
            filter.add(SIGNAL_QUALITY);
            signalManager.registerCallback(signalCallback(), filter);
            subscribedManager = signalManager;
            return true;
        } catch (Throwable t) {
            // registerCallback records the callback locally before asking the service; undo that
            // so the shared manager does not keep a half-registered listener of ours.
            unregisterQuietly(signalManager);
            rejectedManager = signalManager;
            if (report != null) report.add("subscribe", "threw", "subscribe threw " + t);
            return false;
        }
    }

    private void unregisterQuietly(@NonNull CarSignalManager manager) {
        if (signalCallback == null) return;
        try {
            manager.unregisterCallback(signalCallback);
        } catch (Throwable t) {
            Log.w(TAG, "12 V: unregisterCallback failed", t);
        }
    }

    // ── diagnosis helpers ────────────────────────────────────────────────────

    /** How a decoded read went, as an outcome kind: nothing there, a value refused, or a voltage. */
    @NonNull
    private static String verdict(float volts, boolean nothingRead) {
        if (!Float.isNaN(volts)) return "ok";
        return nothingRead ? "empty" : "rejected";
    }

    /** Collects a stable outcome key (what changed?) and a detailed line (what exactly?). */
    private static final class Report {
        final StringBuilder outcome = new StringBuilder();
        final StringBuilder detail = new StringBuilder();

        void add(@NonNull String source, @NonNull String kind, @NonNull String text) {
            outcome.append(source).append('=').append(kind).append(';');
            if (detail.length() > 0) detail.append("; ");
            detail.append(text);
        }
    }

    /** Which implementation actually answers: vendor classes on the boot class path, or ours. */
    @NonNull
    private static String environment(@Nullable ISensor sensors) {
        String adaptApi;
        try {
            adaptApi = AdaptAPI.VERSION;   // a static field, not a compile-time constant
        } catch (Throwable t) {
            adaptApi = "? (" + t.getClass().getSimpleName() + ")";
        }
        return "adaptapi " + adaptApi
                + ", sensors " + (sensors == null ? "null" : sensors.getClass().getName()
                + " via " + loaderName(sensors.getClass()))
                + ", android.car via " + loaderName(android.car.Car.class);
    }

    @NonNull
    private static String loaderName(@NonNull Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? "boot" : loader.getClass().getSimpleName();
    }

    @NonNull
    private static String hex(@Nullable byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
