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

import java.util.Set;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;

/**
 * A temperature read from the vehicle. One class, one instance per sensor — the two differ only
 * in which {@link BrickType} they subscribe to and which view they write into.
 */
final class TempRenderBrick extends TextRenderBrick {

    /** Shown while a subscribed brick has not yet received a plausible value. */
    private static final String PLACEHOLDER = "--°";

    TempRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                    @NonNull Preferences.TempBrickPrefs prefs, @IdRes int viewId) {
        super(host, type, prefs, viewId);
    }

    /**
     * A preset imported from another car may list a sensor this vehicle cannot feed. Such a brick
     * must neither render — it would be a permanently frozen placeholder — nor raise the height
     * floor for something that never appears.
     */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type) && car().isBrickSupported(type);
    }

    @Override
    boolean countsTowardFloor(@NonNull Set<BrickType> order) {
        return activeInLayout(order);
    }

    @Override
    void syncSource(boolean active) {
        if (!hasView()) return;
        CarIntegration car = car();
        if (active) {
            // Subscribe regardless of isBrickSupported(): right after boot the vendor service may
            // not have connected yet and support reads as "unknown/error" — but the SDK queues
            // listener registrations locally, so subscribing now means data starts flowing the
            // moment the service comes up. Visibility is gated separately, and the
            // availability-changed callback re-runs the settings pass when the answer flips.
            if (text.getText().length() == 0) {
                // Placeholder until the first value arrives, so the brick occupies its slot
                // instead of rendering as a zero-width hole.
                text.setText(PLACEHOLDER);
            }
            car.subscribe(type, (brickType, value) -> {
                if (!hasView()) return;
                // Hot path: the vendor SDK pushes a reading about once a second and a cabin
                // temperature usually rounds to the same integer for minutes on end. An
                // unconditional setText would relayout the whole row at that cadence.
                setTextIfChanged(format(value));
            });
        } else {
            car.unsubscribe(type);
            // Reset so a re-added brick starts from the placeholder, not a stale reading.
            text.setText(PLACEHOLDER);
        }
    }

    @Override
    void onDestroy() {
        car().unsubscribe(type);
    }

    private CarIntegration car() {
        return CarIntegrations.get(host.context());
    }

    private static String format(float celsius) {
        // Integer rounding via Math.round avoids "%.0f"-style "-0°" for readings in (-0.5, 0).
        return Math.round(celsius) + "°";
    }
}
