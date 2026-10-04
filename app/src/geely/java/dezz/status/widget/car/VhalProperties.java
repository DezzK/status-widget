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

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Vendor VHAL properties of an eCarX head unit, read through Android's car service. They carry
 * what no AdaptAPI sensor carries on every firmware: the 12 V system voltage, and the fuel level
 * on firmwares that leave the SDK's virtual fuel percentage empty. One car service binding for
 * the process, shared by every reader; reading a property needs {@code CAR_VENDOR_EXTENSION}.
 *
 * <p>The binding is made by the first read, retried after a failure, and written off after
 * {@value #MAX_FAILED_BINDS} failed binds. Until it is up, a read reports why it has nothing.
 * Main thread only.
 */
final class VhalProperties {

    private static final String TAG = GeelyCarIntegration.TAG;

    /** The vendor properties read here are all global. */
    private static final int GLOBAL_AREA = 0;

    /**
     * Pause before retrying a car-service step that failed: asking a connected service for its
     * property manager, or a bind — which waits four times longer after each further failure.
     */
    private static final long CAR_SERVICE_RETRY_MS = 30_000L;
    /** Binds attempted before the car service is written off for this process. */
    private static final int MAX_FAILED_BINDS = 4;

    /** One read of one property: what came back, and how to describe it. */
    static final class Read {
        /** The property's bytes; null when it answered nothing. */
        @Nullable
        final byte[] bytes;
        /**
         * Why the property could not be read, as a category that stays the same across repeats
         * of one failure — so a diagnosis logged on change does not log every poll — or null
         * when the read went through.
         */
        @Nullable
        final String problem;
        /** The raw bytes and status, or the failure in full. */
        @NonNull
        final String detail;

        private Read(@Nullable byte[] bytes, @Nullable String problem, @NonNull String detail) {
            this.bytes = bytes;
            this.problem = problem;
            this.detail = detail;
        }

        /** How this read went once its bytes decoded to {@code value}, as an outcome category. */
        @NonNull
        String outcome(float value) {
            return problem != null ? problem : EcarxReadings.verdict(value, bytes == null);
        }
    }

    private final Context appContext;

    /**
     * The current binding attempt. Callbacks from any other (older) attempt are ignored: the
     * car-lib keeps an abandoned {@code Car}'s connection alive and may still call it.
     */
    @Nullable
    private Binding binding;
    @Nullable
    private CarPropertyManager propertyManager;
    private int failedBinds;
    /** Elapsed-realtime before which no new bind is attempted. */
    private long bindRetryAt;
    /** Elapsed-realtime before which a missing property manager is not asked for again. */
    private long managerRetryAt;
    @NonNull
    private String carServiceState = "not bound yet";

    /** One {@code Car} and the connection callbacks that belong to it. */
    private final class Binding implements ServiceConnection {
        @Nullable
        android.car.Car car;
        boolean everConnected;
        /** The service died after connecting; the car-lib will reconnect this Car by itself. */
        boolean lost;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (binding != this || car == null) return;
            if (lost) {
                // The car-lib reconnected after a service death but kept the managers bound to the
                // dead process, and its own disconnect() skipped the unbind back then. Now that
                // it counts as connected again, disconnect() really tears down and unbinds; start
                // over with a fresh Car.
                binding = null;
                disconnectQuietly(car);
                carServiceState = "service restarted, rebinding";
                ensureCarService();
                return;
            }
            everConnected = true;
            failedBinds = 0;
            acquirePropertyManager(car);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (binding != this) return;
            propertyManager = null;
            if (everConnected) {
                lost = true;
                carServiceState = "service lost, waiting for it";
                return;
            }
            // Never connected: the car-lib gave up after its own bind retries. Nothing is bound.
            noteBindFailed("bind failed");
        }
    }

    VhalProperties(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    /** Read one vendor {@code byte[]} property, binding the car service first if need be. */
    @MainThread
    @NonNull
    Read read(int propertyId) {
        CarPropertyManager manager = propertyManager;
        if (manager == null) {
            Binding current = binding;
            if (current == null) {
                ensureCarService();
            } else if (current.everConnected && !current.lost && current.car != null
                    && SystemClock.elapsedRealtime() >= managerRetryAt) {
                acquirePropertyManager(current.car);
            }
            manager = propertyManager;
        }
        if (manager == null) {
            return new Read(null, failedBinds > 0 ? "bind failed" : carServiceState,
                    carServiceState);
        }
        try {
            CarPropertyValue<byte[]> value =
                    manager.getProperty(byte[].class, propertyId, GLOBAL_AREA);
            byte[] bytes = value != null ? value.getValue() : null;
            return new Read(bytes, null, "[" + hex(bytes) + "] status="
                    + (value != null ? value.getStatus() : "null"));
        } catch (Throwable t) {
            return new Read(null, "threw " + t.getClass().getSimpleName(), "threw " + t);
        }
    }

    /** Drop the car service binding. The instance may be used again. */
    @MainThread
    void release() {
        Binding current = binding;
        binding = null;
        propertyManager = null;
        failedBinds = 0;
        bindRetryAt = 0;
        carServiceState = "released";
        if (current != null && current.car != null) disconnectQuietly(current.car);
    }

    /** Start one bind to Android's car service unless one is running or a retry is not due. */
    private void ensureCarService() {
        if (binding != null) return;
        if (SystemClock.elapsedRealtime() < bindRetryAt) return;
        Binding attempt = new Binding();
        try {
            attempt.car = android.car.Car.createCar(appContext, attempt);
            if (attempt.car == null) {
                // Returned when the device does not declare the automotive feature: no car
                // service will ever be there.
                carServiceState = "no car service (createCar returned null)";
                bindRetryAt = Long.MAX_VALUE;
                return;
            }
            binding = attempt;
            carServiceState = "binding";
            attempt.car.connect();
        } catch (Throwable t) {
            noteBindFailed("bind threw " + t);
        }
    }

    /** Count a failed bind and schedule the next one, or give up after {@link #MAX_FAILED_BINDS}. */
    private void noteBindFailed(@NonNull String what) {
        binding = null;
        failedBinds++;
        boolean givingUp = failedBinds >= MAX_FAILED_BINDS;
        bindRetryAt = givingUp ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() + (CAR_SERVICE_RETRY_MS << (2 * (failedBinds - 1)));
        carServiceState = what + " (" + failedBinds + (givingUp ? ", giving up)" : ", retrying)");
    }

    private void acquirePropertyManager(@NonNull android.car.Car car) {
        try {
            // The Android 9 property manager lists the properties this app may read as it is
            // built; a missing permission can surface right here.
            propertyManager = (CarPropertyManager) car.getCarManager(android.car.Car.PROPERTY_SERVICE);
            carServiceState = propertyManager != null ? "connected" : "no property manager";
        } catch (Throwable t) {
            propertyManager = null;
            carServiceState = "no property manager: " + t;
        }
        if (propertyManager == null) {
            managerRetryAt = SystemClock.elapsedRealtime() + CAR_SERVICE_RETRY_MS;
        }
    }

    private static void disconnectQuietly(@NonNull android.car.Car car) {
        try {
            car.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "VHAL: car service disconnect failed", t);
        }
    }

    @NonNull
    private static String hex(@Nullable byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
