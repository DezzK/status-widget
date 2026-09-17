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

import androidx.annotation.NonNull;

import java.util.Set;

/**
 * Null object for builds (or vehicles) without any car SDK: no metric is supported, declared
 * needs are ignored. A future flavor with no vendor integration can return this from its factory;
 * flavors whose SDK probing fails can also fall back to it.
 */
public final class NoCarIntegration implements CarIntegration {

    @Override
    public boolean isMetricSupported(@NonNull CarMetric metric) {
        return false;
    }

    @Override
    public void setNeeds(@NonNull Listener listener, @NonNull Set<CarMetric> needs) {
    }

    @Override
    public void addAvailabilityListener(@NonNull Runnable listener) {
    }

    @Override
    public void removeAvailabilityListener(@NonNull Runnable listener) {
    }

    @Override
    public void shutdown() {
    }
}
