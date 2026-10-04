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
 *       {@link VhalProperties} — the car service binding the fuel level shares. It is the head
 *       unit's own measurement of its supply, so it needs nothing from the CAN bus; a Tugella
 *       VHAL dump lists it as readable, and it is what the DriveHome project reads on Geely,
 *       Livan, Belgee and Knewstar units.</li>
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

    private static final String TAG = GeelyCarIntegration.TAG;

    /** VPOWERINFO_BATTERY_VOL: vendor group, byte[], global area. */
    static final int VHAL_BATTERY_VOLTAGE = 0x287000E4;

    private static final int SIGNAL_VALUE = CarSignalManager.SignalId_VehBattUSysU;
    private static final int SIGNAL_QUALITY = CarSignalManager.SignalId_VehBattUSysUQf;

    /** Marks a signal no event has delivered yet. */
    private static final int NO_EVENT = Integer.MIN_VALUE;

    /** The VHAL source; null when this process cannot read vendor properties at all. */
    @Nullable
    private final VhalProperties vhal;

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

    EcarxBatteryVoltage(@Nullable VhalProperties vhal) {
        this.vhal = vhal;
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

    /**
     * Drop the signal subscription. The instance may be used again. The VHAL binding is not this
     * object's to drop: other readings share it.
     */
    @MainThread
    void release() {
        CarSignalManager manager = subscribedManager;
        subscribedManager = null;
        rejectedManager = null;
        if (manager != null) unregisterQuietly(manager);
    }

    // ── VHAL ─────────────────────────────────────────────────────────────────

    private float readVhal(@NonNull Report report) {
        if (vhal == null) {
            report.add("vhal", "unavailable", "vhal unavailable");
            return Float.NaN;
        }
        VhalProperties.Read read = vhal.read(VHAL_BATTERY_VOLTAGE);
        float volts = read.problem != null ? Float.NaN
                : EcarxReadings.batteryVoltage(EcarxReadings.littleEndian(read.bytes),
                        EcarxReadings.SIGNAL_ABSENT);
        report.add("vhal", read.outcome(volts),
                "vhal " + read.detail + (read.problem == null ? " -> " + volts : ""));
        return volts;
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
                    from + " " + EcarxReadings.verdict(volts, raw <= 0)
                            + (error != null ? " +error" : ""),
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
}
