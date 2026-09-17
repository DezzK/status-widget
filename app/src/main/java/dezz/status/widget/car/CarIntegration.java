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
 * Abstraction over a vendor car SDK feeding data to car-specific bricks.
 * <p>
 * Each product flavor (Gradle dimension {@code car}) supplies exactly one implementation via
 * {@code dezz.status.widget.car.CarIntegrationFactory} in its own source set — main code must
 * never reference vendor classes. The contract:
 * <ul>
 *   <li>All callbacks are delivered on the main thread.</li>
 *   <li>{@link #isMetricSupported} is cheap and callable any time (settings UI uses it to decide
 *       which bricks to offer); it must return {@code false} on vehicles where the underlying
 *       SDK or the specific signal is unavailable, and never throw.</li>
 *   <li>{@link #setNeeds} declares the complete set of metrics a listener wants, replacing its
 *       previous declaration. Implementations register vendor feeds for the union of all
 *       declarations and should push the latest known value of a newly needed metric
 *       immediately when one is available, so a freshly shown brick doesn't sit empty until the
 *       signal's next change event.</li>
 *   <li>Vendor-side failures are contained: implementations log and stay silent instead of
 *       crashing the widget.</li>
 * </ul>
 */
public interface CarIntegration {

    /** Receives values for the metrics a listener declared. Called on the main thread. */
    interface Listener {
        void onCarValue(@NonNull CarMetric metric, float value);
    }

    /** Whether this vehicle can feed the given metric right now. */
    boolean isMetricSupported(@NonNull CarMetric metric);

    /** Whether this vehicle can feed every one of the given metrics right now. */
    default boolean areMetricsSupported(@NonNull Set<CarMetric> metrics) {
        for (CarMetric metric : metrics) {
            if (!isMetricSupported(metric)) return false;
        }
        return true;
    }

    /**
     * Register a callback (main thread) invoked whenever the answer of {@link #isMetricSupported}
     * may have changed — typically when the vendor platform service finishes its asynchronous
     * connect after boot, or when a signal first delivers data. Registration is multicast: the
     * overlay service and the settings screen both need it and must not evict each other.
     * Implementations with static support (e.g. {@link NoCarIntegration}) may ignore it.
     */
    void addAvailabilityListener(@NonNull Runnable listener);

    /** Unregister a callback added by {@link #addAvailabilityListener}. */
    void removeAvailabilityListener(@NonNull Runnable listener);

    /**
     * Declare everything a listener needs, registering and unregistering vendor feeds to match.
     * Idempotent — a brick calls this on every settings pass with the answer its preferences give
     * today. An empty set removes the listener entirely.
     */
    void setNeeds(@NonNull Listener listener, @NonNull Set<CarMetric> needs);

    /** Release all listeners and vendor resources. The instance is not reusable afterwards. */
    void shutdown();
}
