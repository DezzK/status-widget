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
import java.util.EnumSet;
import java.util.Set;

import dezz.status.widget.car.CarMetric;

/**
 * Fuel in the tank, as a percentage or in liters. Liters need a tank capacity — the user's, or
 * the one the car reports. Without either the brick falls back to the percentage at render time
 * and leaves the preference alone, so the liters readout comes back by itself the moment a
 * capacity becomes known.
 */
final class FuelLevelRenderBrick extends CarTextRenderBrick {

    private final Preferences.FuelLevelBrickPrefs prefs;

    FuelLevelRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.FUEL_LEVEL, host.prefs().fuelLevel, R.id.fuelLevelText);
        this.prefs = host.prefs().fuelLevel;
    }

    @NonNull
    @Override
    protected Set<CarMetric> neededCarMetrics() {
        Set<CarMetric> needs = EnumSet.copyOf(type.requiredCarMetrics());
        if (wantsLiters() && prefs.tankCapacityLiters.get() <= 0) {
            needs.add(CarMetric.FUEL_TANK_CAPACITY_L);
        }
        return needs;
    }

    @NonNull
    @Override
    protected String placeholder() {
        return host.context().getString(wantsLiters()
                ? R.string.car_fuel_liters_placeholder : R.string.car_fuel_percent_placeholder);
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        Float percent = latest.get(CarMetric.FUEL_PERCENT);
        if (percent == null) return null;
        float capacity = tankCapacity(latest);
        if (wantsLiters() && capacity > 0f) {
            return host.context().getString(R.string.car_fuel_liters_format,
                    Math.round(percent / 100f * capacity));
        }
        return host.context().getString(R.string.car_fuel_percent_format, Math.round(percent));
    }

    private boolean wantsLiters() {
        return prefs.unit.get() == Preferences.FuelLevelBrickPrefs.UNIT_LITERS;
    }

    /** The user's capacity when set, otherwise the car's; 0 when neither is known. */
    private float tankCapacity(@NonNull EnumMap<CarMetric, Float> latest) {
        int configured = prefs.tankCapacityLiters.get();
        if (configured > 0) return configured;
        Float reported = latest.get(CarMetric.FUEL_TANK_CAPACITY_L);
        return reported != null ? reported : 0f;
    }
}
