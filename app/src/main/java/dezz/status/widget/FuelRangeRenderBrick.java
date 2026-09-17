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

    FuelRangeRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.FUEL_RANGE, host.prefs().fuelRange, R.id.fuelRangeText);
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
