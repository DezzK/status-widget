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
import java.util.EnumSet;
import java.util.Set;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;

/**
 * A one-line reading fed by the flavor's {@link CarIntegration}. Everything the car bricks share
 * lives here: gating on vehicle support, declaring the metrics to the integration, a placeholder
 * until the first value, and repainting from the last values whenever a preference changes how
 * they are shown — a fuel gauge can sit on one value for many minutes, so waiting for the next
 * reading to apply a new unit is not an option.
 */
abstract class CarTextRenderBrick extends TextRenderBrick implements CarIntegration.Listener {

    /** Last value per metric since the brick became active. */
    private final EnumMap<CarMetric, Float> latest = new EnumMap<>(CarMetric.class);

    protected CarTextRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                                 @NonNull Preferences.CarTextBrickPrefs prefs, @IdRes int viewId) {
        super(host, type, prefs, viewId);
    }

    /** Shown while there is nothing to format yet. */
    @NonNull
    protected abstract String placeholder();

    /** The text for the values received so far, or {@code null} while they are not enough. */
    @Nullable
    protected abstract String format(@NonNull EnumMap<CarMetric, Float> latest);

    /**
     * Everything this brick wants delivered: its required metrics plus any it merely makes use
     * of. Asked on every settings pass, so it may depend on preferences.
     */
    @NonNull
    protected Set<CarMetric> neededCarMetrics() {
        return type.requiredCarMetrics();
    }

    /**
     * A preset imported from another car may list a metric this vehicle cannot feed. Such a brick
     * must neither render — it would be a permanently frozen placeholder — nor raise the height
     * floor for something that never appears.
     */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type) && car().areMetricsSupported(type.requiredCarMetrics());
    }

    @Override
    boolean countsTowardFloor(@NonNull Set<BrickType> order) {
        return activeInLayout(order);
    }

    @Override
    void syncSource(boolean active) {
        if (!hasView()) return;
        if (active) {
            // Declare the needs regardless of support: right after boot the vendor service may
            // not have connected yet and support reads as "unknown/error" — but the SDK queues
            // listener registrations locally, so declaring now means data starts flowing the
            // moment the service comes up. Visibility is gated separately, and the
            // availability-changed callback re-runs the settings pass when the answer flips.
            car().setNeeds(this, neededCarMetrics());
            render();
        } else {
            car().setNeeds(this, EnumSet.noneOf(CarMetric.class));
            // Reset so a re-added brick starts from the placeholder, not a stale reading.
            latest.clear();
            render();
        }
    }

    @Override
    void refreshContent() {
        render();
    }

    @Override
    void onConfigurationChanged() {
        render();
    }

    @Override
    public void onCarValue(@NonNull CarMetric metric, float value) {
        latest.put(metric, value);
        render();
    }

    @Override
    void onDestroy() {
        car().setNeeds(this, EnumSet.noneOf(CarMetric.class));
    }

    private void render() {
        if (!hasView()) return;
        String formatted = format(latest);
        // Hot path: temperature arrives about once a second and usually rounds to the same
        // integer for minutes on end. An unconditional setText would relayout the whole row at
        // that cadence. Placeholder until the first value, so the brick occupies its slot instead
        // of rendering as a zero-width hole.
        setTextIfChanged(formatted != null ? formatted : placeholder());
    }

    protected final CarIntegration car() {
        return CarIntegrations.get(host.context());
    }
}
