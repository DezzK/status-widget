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

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Process-wide access point to the flavor's {@link CarIntegration}. Both the overlay service
 * (data subscriptions) and the settings UI (filtering which bricks to offer) need the same
 * instance, so it lives here rather than inside either of them.
 * <p>
 * {@code CarIntegrationFactory} is intentionally not present in the main source set — every
 * product flavor must define it in {@code app/src/<flavor>/java/dezz/status/widget/car/}. A
 * flavor without vendor hardware support simply returns {@link NoCarIntegration}.
 */
public final class CarIntegrations {

    private static volatile CarIntegration instance;

    private CarIntegrations() {
    }

    @NonNull
    public static CarIntegration get(@NonNull Context context) {
        CarIntegration local = instance;
        if (local == null) {
            synchronized (CarIntegrations.class) {
                local = instance;
                if (local == null) {
                    local = CarIntegrationFactory.create(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }
}
