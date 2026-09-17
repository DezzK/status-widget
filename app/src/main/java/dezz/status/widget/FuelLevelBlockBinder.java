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

import android.view.View;
import android.widget.TextView;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;

/** Binds {@code brick_block_fuel_level.xml} — the unit and, for liters, the tank capacity. */
final class FuelLevelBlockBinder extends BrickBlockBinder {
    private final Preferences.FuelLevelBrickPrefs p;

    private final MaterialAutoCompleteTextView unitDropdown;
    private final View tankCapacityRow;
    private final Slider tankCapacitySlider;
    private final TextView tankCapacityHint;

    FuelLevelBlockBinder(BrickBinderContext ctx, View root, Preferences.FuelLevelBrickPrefs p) {
        super(ctx, root);
        this.p = p;
        unitDropdown = find(R.id.brickFuelLevelUnitDropdown);
        tankCapacityRow = find(R.id.brickFuelLevelTankCapacityRow);
        tankCapacitySlider = find(R.id.brickFuelLevelTankCapacitySlider);
        tankCapacityHint = find(R.id.brickFuelLevelTankCapacityHint);
    }

    @Override
    protected void clearListeners() {
        tankCapacitySlider.clearOnChangeListeners();
    }

    @Override
    protected void bindViews() {
        LabelFormatter capacityFormatter = value -> value < 1f
                ? ctx.activity.getString(R.string.fuel_tank_capacity_auto)
                : ctx.activity.getString(R.string.fuel_tank_capacity_value, (int) value);

        Runnable refresh = () -> {
            boolean liters = p.unit.get() == Preferences.FuelLevelBrickPrefs.UNIT_LITERS;
            tankCapacityRow.setVisibility(liters ? View.VISIBLE : View.GONE);
            // "Auto" is only a promise when the car keeps it; otherwise say plainly that liters
            // will not appear until a capacity is entered.
            boolean carReports = CarIntegrations.get(ctx.activity)
                    .isMetricSupported(CarMetric.FUEL_TANK_CAPACITY_L);
            tankCapacityHint.setText(p.tankCapacityLiters.get() <= 0 && !carReports
                    ? R.string.fuel_tank_capacity_unknown_hint
                    : R.string.fuel_tank_capacity_hint);
        };

        ctx.bindIntDropdown(unitDropdown, R.array.fuel_level_units, p.unit, refresh);
        ctx.bindIntSlider(tankCapacitySlider, p.tankCapacityLiters, capacityFormatter);
        tankCapacitySlider.addOnChangeListener((slider, value, fromUser) -> refresh.run());
        refresh.run();
    }
}
