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

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * GNSS status icon. Owns the platform location callbacks, the staleness demotion tick, and the
 * gnss-share broadcast that supplies the satellite-count / dead-reckoning / spoof badge.
 */
final class GpsRenderBrick extends IconRenderBrick {

    private static final String TAG = "GpsRenderBrick";
    private static final long STATUS_CHECK_INTERVAL_MS = 1000;

    /** Broadcast from the companion gnss-share app; see its README for the contract. */
    private static final String SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    /** Satellite count extra. {@code -1} means "no satellite data" (badge hidden). */
    private static final String EXTRA_SATELLITES_COUNT = "count";
    /**
     * Optional positioning-mode extra, treated as a bit mask (absent / 0 = normal satellite
     * fixing). The two flags are independent — dead reckoning and spoofing-detected can each be
     * set on their own or together (3 = dead reckoning entered because of a detected spoof).
     */
    private static final String EXTRA_MODE = "mode";
    private static final int MODE_DR = 1;     // bit 0: position is dead-reckoned
    private static final int MODE_SPOOF = 2;  // bit 1: GPS spoofing detected
    private static final long SATELLITE_STATUS_TIMEOUT_MS = 30_000L;

    enum State {
        OFF, BAD, GOOD
    }

    private static final int[][] DESIGNS = {
            { R.drawable.ic_status_gps_off, R.drawable.ic_status_gps_bad, R.drawable.ic_status_gps_good },
            { R.drawable.ic_status_filled_gps_off, R.drawable.ic_status_filled_gps_bad, R.drawable.ic_status_filled_gps_good },
            { R.drawable.ic_status_bars_gps_off, R.drawable.ic_status_bars_gps_bad, R.drawable.ic_status_bars_gps_good }
    };

    private static final int[] STATE_COLORS = {
            R.color.status_off,
            R.color.status_warning,
            R.color.status_ok
    };

    private final Preferences.GpsBrickPrefs gpsPrefs;

    private State state = State.OFF;
    private long lastLocationUpdateTime = 0;
    @Nullable
    private LocationManager locationManager;

    private int satellitesCount = -1;
    private int modeFlags = 0;
    private long satellitesCountTimestamp = 0;
    private boolean satelliteReceiverRegistered = false;

    GpsRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.GPS, host.prefs().gps);
        this.gpsPrefs = host.prefs().gps;
    }

    @NonNull
    @Override
    protected OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding) {
        return binding.gnssStatusIcon;
    }

    @NonNull
    @Override
    protected int[][] designs() {
        return DESIGNS;
    }

    @NonNull
    @Override
    protected int[] stateColors() {
        return STATE_COLORS;
    }

    @Override
    protected int stateOrdinal() {
        return state.ordinal();
    }

    /**
     * Satellite count, or a marker when the fix is degraded. Two independent flags: dead reckoning
     * drives the text, spoofing drives the colour, so both read off the same pill (e.g. "DR" on
     * red = fell back to dead reckoning because of a detected spoof).
     */
    @Nullable
    @Override
    protected IconRenderBrick.TextBadge textBadge(int stateIdx, int styleBg) {
        if (!gpsPrefs.showSatelliteBadge.get()) return null;
        if (android.os.SystemClock.uptimeMillis() - satellitesCountTimestamp
                >= SATELLITE_STATUS_TIMEOUT_MS) {
            return null;
        }
        boolean deadReckoning = (modeFlags & MODE_DR) != 0;
        boolean spoofDetected = (modeFlags & MODE_SPOOF) != 0;
        String text;
        if (deadReckoning) {
            text = host.context().getString(R.string.gnss_dr_badge);
        } else if (spoofDetected) {
            // Spoofing but still on GPS: show the marker, not the count — the count is
            // untrustworthy under a spoof and may be absent (some clients report -1).
            text = host.context().getString(R.string.gnss_spoof_badge);
        } else if (satellitesCount > 0) {
            text = String.valueOf(satellitesCount);
        } else {
            return null;
        }
        Context ctx = host.themed();
        if (spoofDetected) {
            // Spoofing detected — red, whether we are on dead reckoning or still on GPS.
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_error),
                    ContextCompat.getColor(ctx, R.color.status_badge_text));
        }
        if (deadReckoning) {
            // Dead reckoning without a spoof — amber (degraded, not an attack).
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_warning),
                    ContextCompat.getColor(ctx, R.color.status_badge_text));
        }
        return new TextBadge(text, styleBg, defaultBadgeForeground());
    }

    @SuppressLint("MissingPermission")
    @Override
    void syncSource(boolean active) {
        if (active) {
            if (locationManager == null) {
                locationManager = host.context().getSystemService(LocationManager.class);
                locationManager.registerGnssStatusCallback(gnssStatusCallback, host.handler());
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                        locationListener, Looper.getMainLooper());
                host.handler().postDelayed(statusCheckRunnable, STATUS_CHECK_INTERVAL_MS);
            }
            if (gpsPrefs.showSatelliteBadge.get()) {
                registerSatelliteReceiver();
            } else {
                unregisterSatelliteReceiver();
            }
            refreshContent();
        } else if (locationManager != null) {
            host.handler().removeCallbacks(statusCheckRunnable);
            unregisterSatelliteReceiver();
            locationManager.removeUpdates(locationListener);
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager = null;
        }
    }

    @Override
    void onDestroy() {
        host.handler().removeCallbacks(statusCheckRunnable);
        host.handler().removeCallbacks(satellitesResetRunnable);
        unregisterSatelliteReceiver();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (Throwable ignored) {
            }
            locationManager = null;
        }
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        refreshContent();
    }

    private void registerSatelliteReceiver() {
        if (satelliteReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(SATELLITE_STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            host.context().registerReceiver(satelliteStatusReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            host.context().registerReceiver(satelliteStatusReceiver, filter);
        }
        satelliteReceiverRegistered = true;
    }

    private void unregisterSatelliteReceiver() {
        if (!satelliteReceiverRegistered) return;
        try {
            host.context().unregisterReceiver(satelliteStatusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        satelliteReceiverRegistered = false;
        host.handler().removeCallbacks(satellitesResetRunnable);
        satellitesCount = -1;
        modeFlags = 0;
    }

    private final Runnable statusCheckRunnable = new Runnable() {
        @Override
        public void run() {
            long silentFor = System.currentTimeMillis() - lastLocationUpdateTime;
            if (silentFor > 10000) {
                setState(State.OFF);
            } else if (silentFor > 5000) {
                setState(State.BAD);
            }
            host.handler().postDelayed(this, STATUS_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable satellitesResetRunnable = () -> {
        satellitesCount = -1;
        modeFlags = 0;
        refreshContent();
    };

    private final BroadcastReceiver satelliteStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra(EXTRA_SATELLITES_COUNT, -1);
            int mode = intent.getIntExtra(EXTRA_MODE, 0);
            Log.d(TAG, "GNSS Share satellites count: " + count + ", mode: " + mode);
            satellitesCount = count;
            modeFlags = mode;
            // Monotonic clock (matching the reset below), so a boot-time wall-clock jump from
            // GPS/NTP sync cannot prematurely expire or freeze the freshness window.
            satellitesCountTimestamp = android.os.SystemClock.uptimeMillis();
            host.handler().removeCallbacks(satellitesResetRunnable);
            host.handler().postDelayed(satellitesResetRunnable, SATELLITE_STATUS_TIMEOUT_MS);
            refreshContent();
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            setState(State.BAD);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            setState(State.OFF);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            setState(State.BAD);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(TAG, "Location changed: " + location);
            lastLocationUpdateTime = System.currentTimeMillis();
            if (location.hasAccuracy() && location.getAccuracy() < 20.0) {
                setState(State.GOOD);
            } else {
                setState(State.BAD);
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };
}
