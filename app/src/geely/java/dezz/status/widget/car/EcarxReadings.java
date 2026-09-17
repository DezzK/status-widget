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

/**
 * Turns raw eCarX AdaptAPI readings into values in {@link CarMetric} units, or {@link Float#NaN}
 * when a reading carries no data. Pure functions with no Android or vendor types, so the rules —
 * most of them learned on real head units rather than from the SDK — are unit-tested on the JVM.
 *
 * <p>The SDK passes most of these signals through without validation: the fuel percentage is the
 * raw signal cast to float, with no clamp, so an overfilled tank reaches us as 101..120.
 */
final class EcarxReadings {

    /**
     * Value of a quality-flag signal marking accurate data. The SDK's own temperature support
     * check accepts exactly this value of the cabin and ambient quality flags and reports
     * anything else as {@code error}.
     */
    static final int QUALITY_ACCURATE = 3;

    /**
     * What a signal that cannot be read is taken as. {@code CarSignalManager.getSignalValue}
     * yields 0 when the platform returns no property value (it may also throw, which callers map
     * to this). A quality flag of 0 is therefore ambiguous — the flag may say "undefined", or the
     * flag signal may simply not exist on this firmware.
     */
    static final int SIGNAL_ABSENT = 0;

    /** Sanity bounds for cabin/ambient readings; outside are CAN glitches or error sentinels. */
    private static final float MIN_TEMPERATURE_C = -40f;
    private static final float MAX_TEMPERATURE_C = 85f;

    /** A full tank reports up to this much above 100 %; beyond it the value is garbage. */
    private static final float MAX_OVERFILLED_PERCENT = 120f;

    /** No passenger car carries fuel for more than this; larger values are sentinels. */
    private static final float MAX_RANGE_KM = 2000f;

    /** Plausible 12 V system voltage, from a flat battery at cranking to a charging alternator. */
    private static final float MIN_VOLTAGE_V = 6f;
    private static final float MAX_VOLTAGE_V = 17f;

    /** Plausible passenger-car fuel tank, liters. */
    private static final float MIN_TANK_L = 20f;
    private static final float MAX_TANK_L = 200f;

    private EcarxReadings() {
    }

    /**
     * {@code Float.MIN_VALUE} is the SDK's "no data yet" sentinel — returned before the platform
     * service connects and for unknown sensor types. It is numerically ~1.4e-45, so without this
     * check it would pass every range test as zero.
     */
    private static boolean isSentinel(float raw) {
        return Float.isNaN(raw) || raw == Float.MIN_VALUE;
    }

    /** Cabin or ambient temperature, °C. */
    static float temperature(float raw) {
        if (isSentinel(raw) || raw < MIN_TEMPERATURE_C || raw > MAX_TEMPERATURE_C) return Float.NaN;
        return raw;
    }

    /**
     * Fuel percentage. Zero is "no data", not an empty tank: firmwares without a fuel gauge
     * signal report a constant 0, and a car never runs on a gauge that reads exactly empty.
     * A tank filled to the neck reports 101..120 and is shown as full.
     */
    static float fuelPercent(float raw) {
        if (isSentinel(raw) || raw <= 0f || raw > MAX_OVERFILLED_PERCENT) return Float.NaN;
        return Math.min(raw, 100f);
    }

    /**
     * Remaining range, km. Zero is what a parked car reports rather than a real prediction, and
     * values past any real tank are the signal's invalid encoding.
     */
    static float rangeKm(float raw) {
        if (isSentinel(raw) || raw <= 0f || raw > MAX_RANGE_KM) return Float.NaN;
        return raw;
    }

    /**
     * 12 V system voltage from the raw {@code VehBattUSysU} signal and its quality flag.
     *
     * <p>The signal's scale is not defined anywhere in the SDK — nothing in it reads this signal.
     * Head units have been seen reporting 13.8 V as 13, 138 and 1380, so the scale is inferred
     * from the magnitude and the result must still land in the plausible range.
     *
     * <p>A quality flag that says anything but accurate rejects the reading — except 0, which an
     * absent flag signal reads as too; the value itself is then the only evidence.
     */
    static float batteryVoltage(int raw, int quality) {
        if (raw <= SIGNAL_ABSENT) return Float.NaN;
        if (quality != SIGNAL_ABSENT && quality != QUALITY_ACCURATE) return Float.NaN;
        float volts;
        if (raw > 1000) {
            volts = raw / 100f;
        } else if (raw > 100) {
            volts = raw / 10f;
        } else {
            volts = raw;
        }
        return volts >= MIN_VOLTAGE_V && volts <= MAX_VOLTAGE_V ? volts : Float.NaN;
    }

    /** Fuel tank capacity: the car info manager reports milliliters. */
    static float tankLiters(float milliliters) {
        if (isSentinel(milliliters)) return Float.NaN;
        float liters = milliliters / 1000f;
        return liters >= MIN_TANK_L && liters <= MAX_TANK_L ? liters : Float.NaN;
    }
}
