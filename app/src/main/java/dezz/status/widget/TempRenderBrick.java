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

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;

import dezz.status.widget.car.CarMetric;

/**
 * A temperature read from the vehicle. One class, one instance per sensor — the two differ only
 * in which {@link BrickType} they render, which view they write into and which icon they show.
 * A temperature has no good or bad to it, so its icon keeps the neutral color.
 */
final class TempRenderBrick extends CarTextRenderBrick {

    TempRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                    @NonNull Preferences.TempBrickPrefs prefs, @IdRes int viewId,
                    @NonNull int... iconDrawables) {
        super(host, type, prefs, viewId, iconDrawables);
    }

    @NonNull
    @Override
    protected String placeholder() {
        return "--°";
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        for (CarMetric metric : type.requiredCarMetrics()) {
            Float celsius = latest.get(metric);
            // Integer rounding via Math.round avoids "%.0f"-style "-0°" for readings in (-0.5, 0).
            if (celsius != null) return Math.round(celsius) + "°";
        }
        return null;
    }
}
