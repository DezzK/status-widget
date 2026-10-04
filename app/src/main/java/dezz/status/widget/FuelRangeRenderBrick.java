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

/** The distance the remaining fuel is predicted to cover, as the instrument cluster shows it. */
final class FuelRangeRenderBrick extends CarTextRenderBrick {

    /** Below this many kilometers the icon warns, and below the second it alarms. */
    private static final float WARNING_KM = 100f;
    private static final float CRITICAL_KM = 50f;

    FuelRangeRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.FUEL_RANGE, host.prefs().fuelRange, R.id.fuelRangeBrick,
                R.drawable.ic_reading_range,
                R.drawable.ic_reading_filled_range,
                R.drawable.ic_reading_bars_range);
    }

    @NonNull
    @Override
    protected ReadingState readingState(@NonNull EnumMap<CarMetric, Float> latest) {
        Float km = latest.get(CarMetric.FUEL_RANGE_KM);
        return km != null ? stateOf(km) : ReadingState.NO_DATA;
    }

    /** How a predicted range, in kilometers, stands. */
    @NonNull
    static ReadingState stateOf(float km) {
        if (km < CRITICAL_KM) return ReadingState.CRITICAL;
        if (km < WARNING_KM) return ReadingState.WARNING;
        return ReadingState.OK;
    }

    @NonNull
    @Override
    protected String placeholder() {
        return host.context().getString(R.string.car_range_placeholder);
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        Float km = latest.get(CarMetric.FUEL_RANGE_KM);
        return km != null ? host.context().getString(R.string.car_range_format, Math.round(km)) : null;
    }
}
