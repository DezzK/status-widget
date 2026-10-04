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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;

import dezz.status.widget.car.CarMetric;

/** The 12 V system voltage — the starter battery while parked, the alternator while running. */
final class BatteryVoltageRenderBrick extends CarTextRenderBrick {

    /**
     * A healthy 12 V system reads about 12.4–12.8 V parked and up to about 14.7 V charging. Below
     * the first bound the battery is weak; below the second it may not start the engine, and above
     * the third the alternator is overcharging it.
     */
    private static final float WARNING_LOW_V = 12.4f;
    private static final float CRITICAL_LOW_V = 11.8f;
    private static final float CRITICAL_HIGH_V = 15f;

    BatteryVoltageRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.BATTERY_VOLTAGE, host.prefs().batteryVoltage,
                R.id.batteryVoltageBrick,
                R.drawable.ic_reading_battery,
                R.drawable.ic_reading_filled_battery,
                R.drawable.ic_reading_bars_battery);
    }

    @NonNull
    @Override
    protected ReadingState readingState(@NonNull EnumMap<CarMetric, Float> latest) {
        Float volts = latest.get(CarMetric.BATTERY_VOLTAGE_V);
        return volts != null ? stateOf(volts) : ReadingState.NO_DATA;
    }

    /** How a system voltage stands. */
    @NonNull
    static ReadingState stateOf(float volts) {
        if (volts < CRITICAL_LOW_V || volts > CRITICAL_HIGH_V) return ReadingState.CRITICAL;
        if (volts < WARNING_LOW_V) return ReadingState.WARNING;
        return ReadingState.OK;
    }

    @NonNull
    @Override
    protected String placeholder() {
        return host.context().getString(R.string.car_voltage_placeholder);
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        Float volts = latest.get(CarMetric.BATTERY_VOLTAGE_V);
        // getString formats with the current locale, so the decimal separator follows it.
        return volts != null ? host.context().getString(R.string.car_voltage_format, volts) : null;
    }
}
