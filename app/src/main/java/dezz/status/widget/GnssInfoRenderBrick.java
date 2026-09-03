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

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * The location readout: every GNSS indication the GPS icon's badge can only hint at, drawn as
 * ICON + VALUE at a size the user picks. The badge is structurally capped —
 * {@code OutlineImageView.drawTextBadge} fixes the pill at a fraction of the icon's short edge —
 * so making it legible would mean growing an icon the user sized for the fix-quality indication.
 *
 * <h3>Why an icon per field</h3>
 * The line carries no words, and a bare {@code 145} is meaningless. An icon is the label: it says
 * WHICH quantity the number is, in a glyph that survives being small, and it says it without a
 * language. It also removes the last places where colour was the only channel — a driver who
 * cannot separate amber from red, or is looking at a sunlit screen, still sees a different SHAPE
 * for dead reckoning, for a detected spoof, and for road anchoring.
 *
 * <h3>Structure</h3>
 * A {@link TableRenderBrick}: the fields are cells dealt into a grid whose height the user sets
 * in rows and whose width follows. One row is the single-line readout. Only the container is in
 * the layout — the cells are built from the list below, because the field list IS a list rather
 * than the fixed shape a layout file is good at.
 */
final class GnssInfoRenderBrick extends TableRenderBrick implements GnssProvider.Listener {

    /** Shown for a field with nothing to say. Not a resource — see {@link TempRenderBrick}. */
    private static final String PLACEHOLDER = "--";
    /**
     * A fix older than this stops backing the readout. The same silence {@link GpsRenderBrick}
     * demotes its icon to OFF on, so the two bricks cannot disagree about the same second — a
     * speed frozen at 58 km/h beside a grey GPS icon is the larger and more readable of the two
     * indications being the wrong one.
     */
    private static final long FIX_STALE_MS = 10_000L;
    /** Satellite counts are clamped so the reserved box stays two digits wide. */
    private static final int MAX_COUNT = 99;
    /** Widest values the reserve has to survive. Beyond them the text is clamped, not clipped. */
    private static final int MAX_ACCURACY_M = 999;
    private static final int MAX_SPEED_KMH = 999;
    private static final int MAX_ALTITUDE_M = 9999;
    private static final long MAX_FIX_AGE_S = 59 * 60 + 59;
    /** Gap between an icon and its value, and between fields — in units of the font size. */
    private static final float ICON_GAP_EM = 0.15f;
    private static final float FIELD_GAP_EM = 0.35f;

    /**
     * The fields, in the fixed order they render in. Order is not configurable and the icons are
     * why it can afford not to be: each field names itself, so the user never has to remember
     * which number came third.
     */
    private enum Field {
        /** Satellite count — the only field whose icon is the subject rather than a label. */
        SATELLITES(R.drawable.ic_gnss_satellites, true),
        /**
         * The matcher anchored the position to a road. A lamp: no value, and it holds its slot
         * dark rather than collapsing (see {@link TableRenderBrick#setLit}).
         *
         * <p>Ordered BEFORE the two alarm lamps although it belongs with them semantically: this
         * is the one of the three that is normally LIT, so putting it here keeps the two usually
         * dark squares at the trailing edge instead of stranding them between the count and the
         * only lamp that is on.
         */
        ROAD(R.drawable.ic_gnss_road, false),
        /** Dead reckoning. A lamp. */
        DR(R.drawable.ic_gnss_dr, false),
        /** Coordinate substitution detected. A lamp. */
        SPOOF(R.drawable.ic_gnss_spoof, false),
        ACCURACY(R.drawable.ic_gnss_accuracy, true),
        SPEED(R.drawable.ic_gnss_speed, true),
        ALTITUDE(R.drawable.ic_gnss_altitude, true),
        FIX_AGE(R.drawable.ic_gnss_fix_age, true);

        @DrawableRes
        final int iconRes;
        /** Whether the field draws a number beside its icon, or is a lamp that is only ever lit. */
        final boolean hasValue;

        Field(@DrawableRes int iconRes, boolean hasValue) {
            this.iconRes = iconRes;
            this.hasValue = hasValue;
        }

        /** Whether the user asked for this field. The one place a switch maps to a cell. */
        boolean enabled(@NonNull Preferences.GnssInfoBrickPrefs p) {
            switch (this) {
                case SATELLITES: return p.showSatellites.get();
                case DR:
                case SPOOF:      return p.showMode.get();
                case ROAD:       return p.showRoad.get();
                case ACCURACY:   return p.showAccuracy.get();
                case SPEED:      return p.showSpeed.get();
                case ALTITUDE:   return p.showAltitude.get();
                case FIX_AGE:    return p.showFixAge.get();
            }
            return false;
        }
    }

    private final Preferences.GnssInfoBrickPrefs gnssPrefs;

    // Resolved once per settings pass rather than per cell per second: ContextCompat.getColor is
    // a resource lookup, and fillCell runs for every cell on every feed callback.
    private int colorPrimary;
    private int colorWarning;
    private int colorError;
    private int colorOk;

    GnssInfoRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.GNSS_INFO);
        this.gnssPrefs = host.prefs().gnssInfo;
    }

    @NonNull
    @Override
    protected Preferences.TableBrickPrefs tablePrefs() {
        return gnssPrefs;
    }

    @Override
    protected int gridId() {
        return R.id.gnssInfoContainer;
    }

    @Override
    void applySettings() {
        super.applySettings();
        Context themed = host.themed();
        colorPrimary = ContextCompat.getColor(themed, R.color.text_primary);
        colorWarning = ContextCompat.getColor(themed, R.color.status_warning);
        colorError = ContextCompat.getColor(themed, R.color.status_error);
        colorOk = ContextCompat.getColor(themed, R.color.status_ok);
    }

    /**
     * Every field can be off, and then there is nothing to draw. Deliberately NOT narrowed by the
     * data: nothing re-runs the visibility phase when a freshness window closes, so a brick that
     * vanished on stale data would have to drive its own root visibility from a callback. It
     * renders placeholders and keeps its slot instead.
     */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type) && !cellSpecs().isEmpty();
    }

    @NonNull
    @Override
    protected List<CellSpec> cellSpecs() {
        List<CellSpec> specs = new ArrayList<>(Field.values().length);
        for (Field field : Field.values()) {
            if (field.enabled(gnssPrefs)) {
                specs.add(new CellSpec(field.name(), field.iconRes, field.hasValue));
            }
        }
        return specs;
    }

    @Override
    protected void fillCell(@NonNull Cell cell) {
        GnssProvider gnss = host.gnss();
        boolean shareFresh = gnss.shareHasData();
        long fixAge = gnss.fixAgeMs();
        // Null once the fix goes stale, which makes every fix-backed formatter fall back to its
        // dashed placeholder — the same policy the share-backed cells already follow.
        Location location = (fixAge >= 0 && fixAge <= FIX_STALE_MS) ? gnss.location() : null;
        switch (Field.valueOf(cell.spec.key)) {
            case SATELLITES:
                setLit(cell, true);
                // The count carries the mode as a COLOUR too — a second, redundant channel on the
                // number the driver looks at first. The lamps still say it in shape, so nothing
                // depends on the colour being seen.
                setTint(cell, modeColor(gnss, shareFresh));
                setValue(cell, satellitesValue(gnss, shareFresh));
                break;
            case DR:
                setLit(cell, shareFresh && gnss.deadReckoning());
                setTint(cell, colorWarning);
                break;
            case SPOOF:
                setLit(cell, shareFresh && gnss.spoofDetected());
                setTint(cell, colorError);
                break;
            case ROAD:
                setLit(cell, shareFresh && gnss.roadAnchored());
                setTint(cell, colorOk);
                break;
            case ACCURACY:
                setLit(cell, true);
                setTint(cell, colorPrimary);
                setValue(cell, accuracyValue(location));
                break;
            case SPEED:
                setLit(cell, true);
                setTint(cell, colorPrimary);
                setValue(cell, speedValue(location));
                break;
            case ALTITUDE:
                setLit(cell, true);
                setTint(cell, colorPrimary);
                setValue(cell, altitudeValue(location));
                break;
            case FIX_AGE:
                setLit(cell, true);
                setTint(cell, colorPrimary);
                setValue(cell, fixAgeValue(gnss));
                break;
        }
    }

    /** Spoof beats dead reckoning beats normal — the same ladder the GPS icon's badge uses. */
    private int modeColor(@NonNull GnssProvider gnss, boolean shareFresh) {
        if (!shareFresh || !gnssPrefs.showMode.get()) return colorPrimary;
        if (gnss.spoofDetected()) return colorError;
        if (gnss.deadReckoning()) return colorWarning;
        return colorPrimary;
    }

    // ── data source ──────────────────────────────────────────────────────────

    @Override
    void syncSource(boolean active) {
        int needs = GnssProvider.NEED_NONE;
        // The service passes bare membership in the user's order, not activeInLayout — so the
        // content switches are tested again here, exactly as they are there.
        if (active && !cellSpecs().isEmpty()) {
            if (gnssPrefs.showSatellites.get() || gnssPrefs.showMode.get()
                    || gnssPrefs.showRoad.get()) {
                needs |= GnssProvider.NEED_SHARE;
            }
            if (gnssPrefs.showAccuracy.get() || gnssPrefs.showSpeed.get()
                    || gnssPrefs.showAltitude.get() || gnssPrefs.showFixAge.get()) {
                needs |= GnssProvider.NEED_LOCATION;
            }
        }
        host.gnss().setNeeds(this, needs);
        refreshContent();
    }

    @Override
    void onDestroy() {
        host.gnss().setNeeds(this, GnssProvider.NEED_NONE);
    }

    @Override
    public void onGnssLocation(@NonNull Location location) {
        refreshContent();
    }

    @Override
    public void onGnssShareStatus() {
        refreshContent();
    }

    /**
     * The fix age counts up on its own, and the three fix-backed values go STALE on their own —
     * without this the staleness gate could never fire on a head unit that gets no gnss-share
     * broadcasts, because nothing else would call back. Costs nothing when all four are off: the
     * provider only runs its ticker while some listener needs a feed.
     */
    @Override
    public void onGnssTick() {
        if (gnssPrefs.showFixAge.get() || gnssPrefs.showAccuracy.get()
                || gnssPrefs.showSpeed.get() || gnssPrefs.showAltitude.get()) {
            refreshContent();
        }
    }

    // ── fields ───────────────────────────────────────────────────────────────

    /**
     * The satellite count, both halves from the SAME sender. The switch adds the used-in-fix
     * number in front of the total; a sender that does not report it (an older gnss-share) falls
     * back to the plain total rather than to a dash, so turning the switch on can never make the
     * readout worse.
     */
    private String satellitesValue(@NonNull GnssProvider gnss, boolean shareFresh) {
        // A live sender with nothing to count (some clients report -1 under a spoof) reads the
        // same as no sender at all — in both cases the number is simply not known.
        if (!shareFresh || gnss.shareSatellites() <= 0) return PLACEHOLDER;
        String total = String.valueOf(clampCount(gnss.shareSatellites()));
        if (!gnssPrefs.satellitesUsedInFix.get()) return total;
        int used = gnss.shareUsedInFix();
        if (used < 0) return total;
        return clampCount(used) + "/" + total;
    }

    /** Horizontal accuracy, the one number a non-specialist reads correctly at a glance. */
    private String accuracyValue(@Nullable Location location) {
        if (location == null || !location.hasAccuracy()) return dashed(unitMeters());
        float meters = location.getAccuracy();
        if (meters >= 1000f) {
            return "±" + String.format(Locale.getDefault(), "%.1f", Math.min(meters, 9999f) / 1000f)
                    + " " + host.context().getString(R.string.gnss_unit_kilometers);
        }
        return "±" + Math.min(Math.round(meters), MAX_ACCURACY_M) + " " + unitMeters();
    }

    private String speedValue(@Nullable Location location) {
        String unit = host.context().getString(R.string.gnss_unit_kmh);
        if (location == null || !location.hasSpeed()) return dashed(unit);
        return Math.min(Math.round(location.getSpeed() * 3.6f), MAX_SPEED_KMH) + " " + unit;
    }

    /**
     * Altitude as the platform reports it: metres above the WGS84 ELLIPSOID, not above sea level.
     * The two differ by the local geoid undulation — tens of metres, and its sign depends on where
     * you are — so this number will not match a road sign or a paper map.
     * {@code Location#getMslAltitudeMeters()} would be the honest one and it is API 34, well above
     * the Android 9–12 head units this app targets.
     */
    private String altitudeValue(@Nullable Location location) {
        if (location == null || !location.hasAltitude()) return dashed(unitMeters());
        long meters = Math.round(location.getAltitude());
        meters = Math.max(-MAX_ALTITUDE_M, Math.min(MAX_ALTITUDE_M, meters));
        return meters + " " + unitMeters();
    }

    /**
     * How long ago the last fix arrived. Seconds while that reads naturally, then m:ss — a driver
     * distinguishes "3 s" from "40 s" but not "212 s" from "254 s".
     */
    private String fixAgeValue(@NonNull GnssProvider gnss) {
        long ageMs = gnss.fixAgeMs();
        if (ageMs < 0) return dashed(unitSeconds());
        long seconds = Math.min(ageMs / 1000, MAX_FIX_AGE_S);
        if (seconds < 60) return seconds + " " + unitSeconds();
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private String unitMeters() {
        return host.context().getString(R.string.gnss_unit_meters);
    }

    private String unitSeconds() {
        return host.context().getString(R.string.gnss_unit_seconds);
    }

    /** A field with no reading keeps its unit, so the readout still says WHAT is missing. */
    private static String dashed(@NonNull String unit) {
        return PLACEHOLDER + " " + unit;
    }

    private static int clampCount(int count) {
        return Math.min(count, MAX_COUNT);
    }

}
