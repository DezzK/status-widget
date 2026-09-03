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

import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * GNSS status icon. Reads the shared {@link GnssProvider} — the fixes that drive its three-state
 * demotion ladder, and the gnss-share data behind its badge: satellite count / dead reckoning /
 * spoof on the pill, and the matcher's road anchoring on its rim.
 */
final class GpsRenderBrick extends IconRenderBrick implements GnssProvider.Listener {

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
     * Satellite count, or a marker when the fix is degraded. Three independent flags on one pill:
     * dead reckoning drives the text, spoofing drives the colour (e.g. "DR" on red = fell back to
     * dead reckoning because of a detected spoof), and the map matcher's road anchoring drives the
     * rim.
     *
     * <p>The green rim mirrors, exactly, the «на дороге» chip in the gnss-share app — the same
     * verdict, decided once on that side and sent already decided, so the two indications cannot
     * disagree about the same second. It is deliberately not restricted to the dead-reckoning
     * states: whether the position is anchored to a road is the same question, and the same answer,
     * while the fix still comes from satellites.
     *
     * <p>One switch covers the whole pill, not one per flag: the badge is a single indication that
     * a user either reads or does not. Someone who wants the same information bigger switches this
     * off and adds {@link GnssInfoRenderBrick} instead.
     */
    @Nullable
    @Override
    protected IconRenderBrick.TextBadge textBadge(int stateIdx, int styleBg) {
        if (!gpsPrefs.showSatelliteBadge.get()) return null;
        if (!host.gnss().shareHasData()) return null;
        boolean deadReckoning = host.gnss().deadReckoning();
        boolean spoofDetected = host.gnss().spoofDetected();
        String text;
        if (deadReckoning) {
            text = host.context().getString(R.string.gnss_dr_badge);
        } else if (spoofDetected) {
            // Spoofing but still on GPS: show the marker, not the count — the count is
            // untrustworthy under a spoof and may be absent (some clients report -1).
            text = host.context().getString(R.string.gnss_spoof_badge);
        } else if (host.gnss().shareSatellites() > 0) {
            text = String.valueOf(host.gnss().shareSatellites());
        } else {
            return null;
        }
        Context ctx = host.themed();
        int ring = host.gnss().roadAnchored()
                ? ContextCompat.getColor(ctx, R.color.status_ok)
                : 0;
        if (spoofDetected) {
            // Spoofing detected — red, whether we are on dead reckoning or still on GPS.
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_error),
                    ContextCompat.getColor(ctx, R.color.status_badge_text), ring);
        }
        if (deadReckoning) {
            // Dead reckoning without a spoof — amber (degraded, not an attack).
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_warning),
                    ContextCompat.getColor(ctx, R.color.status_badge_text), ring);
        }
        return new TextBadge(text, styleBg, defaultBadgeForeground(), ring);
    }

    /**
     * The icon needs fixes for its demotion ladder, and the gnss-share feed only while the badge
     * is switched on. Declaring both through the shared provider is what lets the readout brick
     * ask for the same feeds without a second registration.
     */
    @Override
    void syncSource(boolean active) {
        int needs = GnssProvider.NEED_NONE;
        if (active) {
            needs = GnssProvider.NEED_LOCATION | GnssProvider.NEED_GNSS_STATUS;
            if (gpsPrefs.showSatelliteBadge.get()) {
                needs |= GnssProvider.NEED_SHARE;
            }
        }
        host.gnss().setNeeds(this, needs);
        if (active) {
            refreshContent();
        }
    }

    @Override
    void onDestroy() {
        host.gnss().setNeeds(this, GnssProvider.NEED_NONE);
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        refreshContent();
    }

    // ── GnssProvider.Listener ────────────────────────────────────────────────

    @Override
    public void onGnssLocation(@NonNull Location location) {
        lastLocationUpdateTime = System.currentTimeMillis();
        if (location.hasAccuracy() && location.getAccuracy() < 20.0) {
            setState(State.GOOD);
        } else {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssStarted() {
        setState(State.BAD);
    }

    @Override
    public void onGnssStopped() {
        setState(State.OFF);
    }

    @Override
    public void onGnssFirstFix(int ttffMillis) {
        setState(State.BAD);
    }

    /**
     * The demotion ladder: a provider that goes quiet is not a provider that says "off", so the
     * icon decays on its own rather than waiting for a callback that will never come.
     */
    @Override
    public void onGnssTick() {
        long silentFor = System.currentTimeMillis() - lastLocationUpdateTime;
        if (silentFor > 10000) {
            setState(State.OFF);
        } else if (silentFor > 5000) {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssShareStatus() {
        refreshContent();
    }
}
