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
import androidx.annotation.Nullable;

import dezz.status.widget.BrickType;

/**
 * Null object for builds (or vehicles) without any car SDK: no brick is supported, subscriptions
 * are ignored. A future flavor with no vendor integration can return this from its factory;
 * flavors whose SDK probing fails can also fall back to it.
 */
public final class NoCarIntegration implements CarIntegration {

    @Override
    public boolean isBrickSupported(@NonNull BrickType type) {
        return false;
    }

    @Override
    public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
    }

    @Override
    public void unsubscribe(@NonNull BrickType type) {
    }

    @Override
    public void setAvailabilityChangedListener(@Nullable Runnable listener) {
    }

    @Override
    public void shutdown() {
    }
}
