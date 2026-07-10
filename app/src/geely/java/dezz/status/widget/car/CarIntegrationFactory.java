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
 * Geely flavor: eCarX AdaptAPI head units (Geely Monjaro and other eCarX-based vehicles).
 * <p>
 * Every product flavor provides its own copy of this class — the main source set deliberately
 * has none, so forgetting it in a new flavor is a compile error, not a runtime surprise.
 */
public final class CarIntegrationFactory {

    private CarIntegrationFactory() {
    }

    @NonNull
    public static CarIntegration create(@NonNull Context appContext) {
        return new GeelyCarIntegration(appContext);
    }
}
