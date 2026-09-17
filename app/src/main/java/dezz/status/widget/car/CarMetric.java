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

/**
 * One quantity a vehicle can report, in a vendor-neutral unit. This — not {@link
 * dezz.status.widget.BrickType} — is what a {@link CarIntegration} is keyed on, for two reasons.
 *
 * <p>A brick is not a sensor. The fuel brick renders a percentage but also wants the tank
 * capacity to offer a reading in liters, and a brick keyed on itself could hold only one
 * subscription. Conversely one metric can feed several bricks at once.
 *
 * <p>And unlike {@code BrickType}, whose {@code name()} is persisted in {@code brickOrder} and
 * in every per-brick preference key, this enum is runtime-only: nothing stores it, so it can be
 * renamed and reordered freely as vendor knowledge changes.
 */
public enum CarMetric {

    /** Cabin air temperature, °C. */
    CABIN_TEMPERATURE_C,

    /** Outside air temperature, °C. */
    AMBIENT_TEMPERATURE_C,

    /** Fuel in the tank, percent of its capacity, 0..100. */
    FUEL_PERCENT,

    /** Distance the remaining fuel is predicted to cover, km. */
    FUEL_RANGE_KM,

    /** Low-voltage (12 V) system supply, volts — the starter battery, not a traction pack. */
    BATTERY_VOLTAGE_V,

    /**
     * Usable fuel-tank capacity, liters. A vehicle constant rather than a reading: it is
     * delivered once, when the integration learns it, and never changes afterwards.
     */
    FUEL_TANK_CAPACITY_L,
}
