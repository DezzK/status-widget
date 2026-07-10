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

import dezz.status.widget.BrickType;

/**
 * Abstraction over a vendor car SDK feeding data to car-specific bricks.
 * <p>
 * Each product flavor (Gradle dimension {@code car}) supplies exactly one implementation via
 * {@code dezz.status.widget.car.CarIntegrationFactory} in its own source set — main code must
 * never reference vendor classes. The contract:
 * <ul>
 *   <li>All callbacks are delivered on the main thread.</li>
 *   <li>{@link #isBrickSupported} is cheap and callable any time (settings UI uses it to decide
 *       which bricks to offer); it must return {@code false} on vehicles where the underlying
 *       SDK or the specific sensor is unavailable, and never throw.</li>
 *   <li>{@link #subscribe} replaces any previous listener for the same brick; implementations
 *       should push the latest known value immediately when one is available, so a freshly shown
 *       brick doesn't sit empty until the sensor's next change event.</li>
 *   <li>Vendor-side failures are contained: implementations log and stay silent instead of
 *       crashing the widget.</li>
 * </ul>
 */
public interface CarIntegration {

    /** Receives values for a subscribed brick. Called on the main thread. */
    interface ValueListener {
        void onValue(@NonNull BrickType type, float value);
    }

    /** Whether this vehicle can feed the given brick right now. */
    boolean isBrickSupported(@NonNull BrickType type);

    /**
     * Register a callback (main thread) invoked whenever the answer of {@link #isBrickSupported}
     * may have changed — typically when the vendor platform service finishes its asynchronous
     * connect after boot. The widget re-applies brick visibility in response. Pass {@code null}
     * to clear. Implementations with static support (e.g. {@link NoCarIntegration}) may ignore it.
     */
    void setAvailabilityChangedListener(@androidx.annotation.Nullable Runnable listener);

    /** Start delivering values for the brick. Replaces any existing subscription for it. */
    void subscribe(@NonNull BrickType type, @NonNull ValueListener listener);

    /** Stop delivering values for the brick. No-op when not subscribed. */
    void unsubscribe(@NonNull BrickType type);

    /** Release all subscriptions and vendor resources. The instance is not reusable afterwards. */
    void shutdown();
}
