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

import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor;

/**
 * Where one {@link CarMetric} comes from on an eCarX head unit, and how far the SDK's own support
 * answer can be trusted for it. Everything here was read out of the AdaptAPI implementation
 * rather than its interfaces, which is why several choices contradict what the constant names
 * suggest.
 */
final class EcarxMetricSpec {

    enum Transport {
        /**
         * {@code ISensor} sensor types, most preferred first. Pushes are taken from the first
         * type; periodic direct reads try the types in order, and then the spec's VHAL property
         * if it has one — which is where a fallback source gets its turn.
         */
        SENSOR,
        /**
         * Pulled on a timer by {@link EcarxBatteryVoltage}, which combines a VHAL property with a
         * CAN signal and owns both ids. {@link #ids} is empty.
         */
        SIGNAL,
        /** {@code ICarInfo} constant, read once during the probe. {@link #ids} holds the info id. */
        CAR_INFO,
    }

    interface FloatDecoder {
        float decode(float raw);
    }

    interface BytesDecoder {
        float decode(@Nullable byte[] bytes);
    }

    /** {@link #vhalProperty} of a spec without one. VHAL property id 0 is INVALID. */
    static final int NO_VHAL_PROPERTY = 0;

    /** REMAINING_OIL: vendor group, byte[], global area — the fuel gauge, in percent. */
    static final int VHAL_REMAINING_OIL = 0x28700207;

    @NonNull
    final Transport transport;
    @NonNull
    final int[] ids;
    /**
     * Whether {@code isSensorSupported} carries information for this metric. For temperatures it
     * does: the SDK derives it from the sensor's quality flag, which takes a live answer from the
     * platform service. For the fuel sensors it does not — the SDK returns {@code active}
     * unconditionally, without asking the car — so support must be proven by a decoded reading,
     * and such an answer must never count as evidence that the SDK works at all.
     */
    final boolean supportApiMeaningful;
    /** Decoder for SENSOR and CAR_INFO readings; unused for SIGNAL. */
    @Nullable
    final FloatDecoder decoder;
    /**
     * A vendor VHAL property read through {@link VhalProperties} when every sensor type comes up
     * empty, or {@link #NO_VHAL_PROPERTY}. Read directly only: nothing pushes it.
     */
    final int vhalProperty;
    /** Decoder for {@link #vhalProperty}; null when there is none. */
    @Nullable
    final BytesDecoder vhalDecoder;

    boolean hasVhalProperty() {
        return vhalProperty != NO_VHAL_PROPERTY;
    }

    private EcarxMetricSpec(@NonNull Transport transport, @NonNull int[] ids,
                            boolean supportApiMeaningful, @Nullable FloatDecoder decoder) {
        this(transport, ids, supportApiMeaningful, decoder, NO_VHAL_PROPERTY, null);
    }

    private EcarxMetricSpec(@NonNull Transport transport, @NonNull int[] ids,
                            boolean supportApiMeaningful, @Nullable FloatDecoder decoder,
                            int vhalProperty, @Nullable BytesDecoder vhalDecoder) {
        this.transport = transport;
        this.ids = ids;
        this.supportApiMeaningful = supportApiMeaningful;
        this.decoder = decoder;
        this.vhalProperty = vhalProperty;
        this.vhalDecoder = vhalDecoder;
    }

    private static final EcarxMetricSpec CABIN_TEMPERATURE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR}, true, EcarxReadings::temperature);

    private static final EcarxMetricSpec AMBIENT_TEMPERATURE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT}, true, EcarxReadings::temperature);

    /**
     * The virtual percentage is the raw fuel-level signal as a float. {@code SENSOR_TYPE_FUEL_LEVEL}
     * is deliberately not a fallback: despite its name the SDK scales it to milliliters. Firmwares
     * whose sensor implementation leaves the virtual percentage empty — seen on a Tugella (AdaptAPI
     * 1.1.18.9), and per DriveHome on Livan and Knewstar, where the fuel sensors read 0 — still
     * carry the gauge in the VHAL property {@code REMAINING_OIL}, which DriveHome reads first.
     */
    private static final EcarxMetricSpec FUEL_PERCENT = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{IVirtualSensor.TYPE_FUEL_PERCENTAGE}, false, EcarxReadings::fuelPercent,
            VHAL_REMAINING_OIL, EcarxReadings::vhalFuelPercent);

    /**
     * The fuel-only range first. The combined range adds the electric range of a hybrid on top,
     * and the SDK builds it as a plain sum of two signals that skips even its own "-1 means no
     * signal" check — so it is only a fallback for firmwares without the first, read directly
     * rather than pushed. Neither source is otherwise validated by the SDK;
     * {@link EcarxReadings#rangeKm} is the only filter.
     */
    private static final EcarxMetricSpec FUEL_RANGE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL, ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE},
            false, EcarxReadings::rangeKm);

    /**
     * No sensor carries the 12 V system voltage; see {@link EcarxBatteryVoltage} for the two
     * sources that do. Their support cannot be asked either: {@code getFunctionStatus} resolves
     * ids as property-adapter types, so for a raw signal id it answers not available or throws.
     */
    private static final EcarxMetricSpec BATTERY_VOLTAGE = new EcarxMetricSpec(Transport.SIGNAL,
            new int[0], false, null);

    /**
     * Read from the car configuration. {@code isCarInfoSupported} does not list this id at all
     * and answers {@code notavailable}, although {@code getCarInfoFloat} serves it.
     */
    private static final EcarxMetricSpec TANK_CAPACITY = new EcarxMetricSpec(Transport.CAR_INFO,
            new int[]{ICarInfo.FLT_INFO_FUEL_CAPACITY}, false, EcarxReadings::tankLiters);

    /** The spec for a metric, or {@code null} when this flavor has no source for it. */
    @Nullable
    static EcarxMetricSpec of(@NonNull CarMetric metric) {
        switch (metric) {
            case CABIN_TEMPERATURE_C:
                return CABIN_TEMPERATURE;
            case AMBIENT_TEMPERATURE_C:
                return AMBIENT_TEMPERATURE;
            case FUEL_PERCENT:
                return FUEL_PERCENT;
            case FUEL_RANGE_KM:
                return FUEL_RANGE;
            case BATTERY_VOLTAGE_V:
                return BATTERY_VOLTAGE;
            case FUEL_TANK_CAPACITY_L:
                return TANK_CAPACITY;
            default:
                return null;
        }
    }
}
