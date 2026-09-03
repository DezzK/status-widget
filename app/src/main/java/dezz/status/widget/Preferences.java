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
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Preferences {
    public static abstract class Preference {
        final Preferences preferences;
        final String key;

        public Preference(Preferences preferences, String key) {
            this.preferences = preferences;
            this.key = key;
        }

        /** The raw storage key — needed where a pref has to be passed across an Intent. */
        public String key() {
            return key;
        }

        public void reset() {
            preferences.prefs.edit().remove(key).apply();
        }
    }

    public static final class Bool extends Preference {
        private final boolean defaultValue;

        public Bool(Preferences preferences, String key, boolean defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            return preferences.prefs.getBoolean(key, defaultValue);
        }

        public void set(boolean value) {
            preferences.prefs.edit().putBoolean(key, value).apply();
        }
    }

    public static final class StringSet extends Preference {
        public StringSet(Preferences preferences, String key) {
            super(preferences, key);
        }

        public Set<String> get() {
            Set<String> stored = preferences.prefs.getStringSet(key, Collections.emptySet());
            // SharedPreferences may return a backing collection — copy to avoid surprises on edit().
            return new HashSet<>(stored);
        }

        public void set(Set<String> value) {
            preferences.prefs.edit().putStringSet(key, new HashSet<>(value)).apply();
        }
    }

    public static final class Int extends Preference {
        private final int defaultValue;

        public Int(Preferences preferences, String key, int defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public int get() {
            return preferences.prefs.getInt(key, defaultValue);
        }

        public void set(int value) {
            preferences.prefs.edit().putInt(key, value).apply();
        }
    }

    public static final class Str extends Preference {
        private final String defaultValue;

        public Str(Preferences preferences, String key, String defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public String get() {
            return preferences.prefs.getString(key, defaultValue);
        }

        public void set(String value) {
            preferences.prefs.edit().putString(key, value).apply();
        }
    }

    /**
     * Everything a brick has regardless of what it draws: geometry, outline, opacity, per-app
     * hiding and status-bar placement. Subclasses add only what their kind of brick can actually
     * use — {@link TextBrickPrefs} the font, {@link IconBrickPrefs} the icon size — so a new brick
     * never re-declares the shared set.
     *
     * <p>Every key is {@code prefix + PascalSuffix}, and the bare prefix doubles as the wipe
     * pattern in {@link Preferences#resetBrick(BrickType)}. So a prefix must not be a textual
     * prefix of another brick's prefix — asserted at construction — and must not be a prefix of a
     * global key either, or "Reset brick" would silently clear an unrelated setting. No global
     * pref collides today. Note that a stored key can outlive its field: bundled presets still
     * carry {@code mediaEnabled}, so importing one re-creates a key under the {@code media}
     * prefix that resetBrick will wipe — harmless precisely because nothing reads it any more.
     */
    public static abstract class BrickPrefs {
        public final String prefix;
        /**
         * The brick's one "how big" pref — font size for text, icon size for icons. The key
         * suffix and the defaults differ per kind, so they come in through the constructor
         * rather than through an override.
         */
        public final Int size;
        /** Label and content description for {@link #size}. */
        @StringRes
        public final int sizeLabelRes;
        public final int sizeMin;
        public final int sizeMax;
        public final Int outlineAlpha;
        public final Int outlineWidth;
        public final Int marginStart;
        public final Int marginEnd;
        public final Int adjustY;
        /** Apps where the brick should be hidden when its own list is in effect. */
        public final StringSet hideInPackages;
        /**
         * If non-empty, the {@link BrickType#name()} of another brick whose list to inherit.
         * Empty string means use {@link #hideInPackages}.
         */
        public final Str hideSource;
        /** Position group inside status-bar mode: 0 = start, 1 = center, 2 = end. */
        public final Int statusAlignment;
        /**
         * When the brick is hidden by a foreground-app match, reserve its space instead of
         * collapsing siblings. Maps to {@code View.INVISIBLE} vs {@code View.GONE}.
         */
        public final Bool hideKeepsSpace;
        /** Whole-element opacity 0..255 — applied to the View via {@code setAlpha(value/255f)}. */
        public final Int contentAlpha;

        protected BrickPrefs(Preferences p, String prefix, String sizeKeySuffix, int defaultSize,
                             @StringRes int sizeLabelRes, int sizeMin, int sizeMax) {
            this.prefix = prefix;
            this.sizeLabelRes = sizeLabelRes;
            this.sizeMin = sizeMin;
            this.sizeMax = sizeMax;
            size = new Int(p, prefix + sizeKeySuffix, defaultSize);
            outlineAlpha = new Int(p, prefix + "OutlineAlpha", 0xAA);
            outlineWidth = new Int(p, prefix + "OutlineWidth", 2);
            marginStart = new Int(p, prefix + "MarginStart", 0);
            marginEnd = new Int(p, prefix + "MarginEnd", 0);
            adjustY = new Int(p, prefix + "AdjustY", 0);
            hideInPackages = new StringSet(p, prefix + "HideInPackages");
            hideSource = new Str(p, prefix + "HideSource", "");
            statusAlignment = new Int(p, prefix + "StatusAlignment", 0);
            hideKeepsSpace = new Bool(p, prefix + "HideKeepsSpace", false);
            contentAlpha = new Int(p, prefix + "ContentAlpha", 255);
        }

        /**
         * Which parts of the shared brick card this brick shows — the answer the settings screen
         * used to get from a per-type switch.
         *
         * <p>Always returns a FRESH mutable set: overrides are expected to take
         * {@code super.controls()} and add/remove, so the result must never be cached in a field.
         */
        public EnumSet<BrickControl> controls() {
            return EnumSet.of(
                    BrickControl.COL_SIZE,
                    BrickControl.COL_ADJUST_Y,
                    BrickControl.ROW_OUTLINE,
                    BrickControl.ROW_CONTENT_ALPHA,
                    BrickControl.ROW_MARGIN,
                    BrickControl.SHARED_STATUS_ALIGNMENT);
        }
    }

    /** A brick that draws text. Adds the font on top of everything in {@link BrickPrefs}. */
    public static class TextBrickPrefs extends BrickPrefs {
        /** Same object as {@link BrickPrefs#size} — for text bricks the size IS the font size. */
        public final Int fontSize;
        /** {@link Fonts.Family#key} of the chosen font family. */
        public final Str fontFamily;
        public final Bool fontBold;
        public final Bool fontItalic;

        public TextBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            super(p, prefix, "FontSize", defaultFontSize, R.string.brick_font_size, 10, 500);
            fontSize = size;
            fontFamily = new Str(p, prefix + "FontFamily", Fonts.DEFAULT_KEY);
            fontBold = new Bool(p, prefix + "FontBold", false);
            fontItalic = new Bool(p, prefix + "FontItalic", false);
        }

        /**
         * {@link BrickControl#BLOCK_FONT} is added here and NOWHERE else — the settings screen
         * casts to this class when it sees the flag, and that cast is only safe while this stays
         * the single origin.
         */
        @Override
        public EnumSet<BrickControl> controls() {
            EnumSet<BrickControl> set = super.controls();
            set.add(BrickControl.BLOCK_FONT);
            return set;
        }
    }

    /**
     * A text brick that also owns a horizontal alignment of its own content. Storage only —
     * whether the brick shows the shared status-alignment dropdown or hosts its own inside its
     * block is decided by the brick, not by owning this field. Keeping the two apart means a
     * brick can gain an alignment pref without losing a working control.
     */
    public static abstract class AlignedTextBrickPrefs extends TextBrickPrefs {
        /** Horizontal alignment of the brick's own text: 0/1/2 = start/center/end. */
        public final Int alignment;

        protected AlignedTextBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            super(p, prefix, defaultFontSize);
            alignment = new Int(p, prefix + "Alignment", 0);
        }
    }

    /** Date brick — date number, day of week, formatting and ordering options. */
    public static final class DateBrickPrefs extends AlignedTextBrickPrefs {
        public final Bool showDate;
        public final Bool showDayOfWeek;
        public final Bool showFullName;
        public final Bool dateBeforeDayOfWeek;
        public final Bool oneLineLayout;

        public DateBrickPrefs(Preferences p) {
            super(p, "date", 20);
            showDate = new Bool(p, "dateShowDate", true);
            showDayOfWeek = new Bool(p, "dateShowDayOfWeek", true);
            showFullName = new Bool(p, "dateShowFullName", false);
            dateBeforeDayOfWeek = new Bool(p, "dateBeforeDayOfWeek", false);
            oneLineLayout = new Bool(p, "dateOneLineLayout", false);
        }

        /**
         * The date block pairs its own status-alignment dropdown with the text-alignment one in a
         * single two-column row, so the shared dropdown above would be a duplicate.
         */
        @Override
        public EnumSet<BrickControl> controls() {
            EnumSet<BrickControl> set = super.controls();
            set.remove(BrickControl.SHARED_STATUS_ALIGNMENT);
            return set;
        }
    }

    /** Media brick — has its own max-width to bound marquee scrolling. */
    public static final class MediaBrickPrefs extends AlignedTextBrickPrefs {
        public final Int maxWidth;
        /** Whether to show the app-name line above the track title. */
        public final Bool showSource;
        /** {@code true} → render as "title — artist"; {@code false} (default) → "artist — title". */
        public final Bool titleFirst;
        /** Vertical gap (px) between the app-name line and the track-title line. */
        public final Int lineGap;
        /**
         * {@code true} (default) → on overflow scroll the text continuously past the max-width;
         * {@code false} → render statically up to max-width and cut off with an ellipsis.
         */
        public final Bool marqueeEnabled;
        /**
         * Show a thin progress bar under the title line that fills as the current track plays.
         * Default on. Off-state hides the bar entirely without affecting the rest of the brick.
         */
        public final Bool progressBarEnabled;
        /**
         * Show the track's total duration to the right of the title (e.g. "4:56"). Default on.
         * Hidden automatically when the player doesn't report a usable duration.
         */
        public final Bool showDuration;
        /**
         * Show the play/pause indicator (a drawn triangle / two bars) at the head of the line it
         * rides on — the source line when {@link #showSource} is on, the title line otherwise.
         * Default on. Independent of the two lines' own visibility.
         */
        public final Bool showPlaybackState;

        /**
         * Duration text settings — independent from the title's font so the user can tune it to a
         * smaller / less prominent style without affecting the track subtitle. Font family / bold
         * / italic are inherited from the title (these covers the common case; can be split out
         * later if anyone asks).
         */
        public final Int durationFontSize;
        public final Int durationContentAlpha;
        public final Int durationOutlineAlpha;
        public final Int durationOutlineWidth;

        /**
         * Source-line ("now playing in &lt;app&gt;") text settings. Title keeps using the
         * inherited {@code TextBrickPrefs} fields (fontSize, fontFamily, fontBold/Italic,
         * outlineAlpha/Width, contentAlpha) so existing presets keep working.
         */
        public final Int sourceFontSize;
        public final Str sourceFontFamily;
        public final Bool sourceFontBold;
        public final Bool sourceFontItalic;
        public final Int sourceContentAlpha;
        public final Int sourceOutlineAlpha;
        public final Int sourceOutlineWidth;
        /**
         * Horizontal alignment of the source line within the media container: 0/1/2 =
         * start/center/end. The title uses the inherited {@link AlignedTextBrickPrefs#alignment}
         * pref so old presets keep working unchanged.
         */
        public final Int sourceAlignment;

        public MediaBrickPrefs(Preferences p) {
            super(p, "media", 20);
            maxWidth = new Int(p, "mediaMaxWidth", 500);
            showSource = new Bool(p, "mediaShowSource", true);
            titleFirst = new Bool(p, "mediaTitleFirst", false);
            lineGap = new Int(p, "mediaLineGap", 0);
            marqueeEnabled = new Bool(p, "mediaMarqueeEnabled", true);
            progressBarEnabled = new Bool(p, "mediaProgressBarEnabled", true);
            showDuration = new Bool(p, "mediaShowDuration", true);
            showPlaybackState = new Bool(p, "mediaShowPlaybackState", true);
            durationFontSize = new Int(p, "mediaDurationFontSize", 16);
            durationContentAlpha = new Int(p, "mediaDurationContentAlpha", 255);
            durationOutlineAlpha = new Int(p, "mediaDurationOutlineAlpha", 0xAA);
            durationOutlineWidth = new Int(p, "mediaDurationOutlineWidth", 2);
            sourceFontSize = new Int(p, "mediaSourceFontSize", 20);
            sourceFontFamily = new Str(p, "mediaSourceFontFamily", Fonts.DEFAULT_KEY);
            sourceFontBold = new Bool(p, "mediaSourceFontBold", false);
            sourceFontItalic = new Bool(p, "mediaSourceFontItalic", false);
            sourceContentAlpha = new Int(p, "mediaSourceContentAlpha", 255);
            sourceOutlineAlpha = new Int(p, "mediaSourceOutlineAlpha", 0xAA);
            sourceOutlineWidth = new Int(p, "mediaSourceOutlineWidth", 2);
            sourceAlignment = new Int(p, "mediaSourceAlignment", 0);
        }

        /**
         * Media drops only what it genuinely splits three ways between source, title and
         * duration — size, outline, opacity and the font picker. It keeps the vertical offset,
         * the margins and the status-bar alignment, which apply to the brick as a whole and
         * which its block used to duplicate against these very same preferences.
         */
        @Override
        public EnumSet<BrickControl> controls() {
            EnumSet<BrickControl> set = super.controls();
            set.remove(BrickControl.COL_SIZE);
            set.remove(BrickControl.ROW_OUTLINE);
            set.remove(BrickControl.ROW_CONTENT_ALPHA);
            set.remove(BrickControl.BLOCK_FONT);
            return set;
        }
    }

    /**
     * Cabin and outside temperature. Intentionally empty today: it exists so the two car bricks
     * share one type instead of being bare {@link TextBrickPrefs}, and so that a future unit /
     * decimals / placeholder pref lands here instead of leaking onto every text brick (adding it
     * to {@link TextBrickPrefs} would mint timeUnit / dateUnit / mediaUnit keys for bricks that
     * have no use for them).
     */
    public static final class TempBrickPrefs extends TextBrickPrefs {
        public TempBrickPrefs(Preferences p, String prefix) {
            super(p, prefix, 40);
        }
    }

    /**
     * A brick that draws an icon. Adds nothing of its own — an icon brick is exactly the base set,
     * with {@link BrickPrefs#size} meaning the icon's edge length.
     */
    public static class IconBrickPrefs extends BrickPrefs {
        public IconBrickPrefs(Preferences p, String prefix) {
            super(p, prefix, "Size", 70, R.string.brick_size, 10, 600);
        }
    }

    /** GPS brick adds the satellite-count badge toggle. */
    public static final class GpsBrickPrefs extends IconBrickPrefs {
        public final Bool showSatelliteBadge;

        public GpsBrickPrefs(Preferences p) {
            super(p, "gps");
            showSatelliteBadge = new Bool(p, "gpsShowSatelliteBadge", true);
        }
    }

    /**
     * A brick whose content is a TABLE of cells rather than one run of text: an ordered list of
     * small readouts laid out into a grid. It adds only the two questions a grid asks that a line
     * does not — how many rows to use, and how far apart to set the cells — and leaves everything
     * about what a cell contains to the brick.
     *
     * <p>{@link #rows} is the control rather than a column count because vertical space is the
     * scarce one on a head unit: the user says how tall the brick may get and the columns follow.
     * One row is the single-line layout, which is why there is no separate boolean for it.
     */
    public static abstract class TableBrickPrefs extends TextBrickPrefs {
        /** How many rows the cells are dealt into; columns are derived from the cell count. */
        public final Int rows;
        /** Space between cells, in px — horizontally between columns, vertically between rows. */
        public final Int cellGap;

        protected TableBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            super(p, prefix, defaultFontSize);
            rows = new Int(p, prefix + "Rows", 1);
            cellGap = new Int(p, prefix + "CellGap", 12);
        }

        /**
         * {@link BrickControl#ROW_TABLE} is added here and NOWHERE else — the settings screen
         * casts to this class when it sees the flag, and that cast is only safe while this stays
         * the single origin.
         */
        @Override
        public EnumSet<BrickControl> controls() {
            EnumSet<BrickControl> set = super.controls();
            set.add(BrickControl.ROW_TABLE);
            return set;
        }
    }

    /**
     * The GNSS readout brick — everything the GPS icon's badge can only hint at, drawn as icon +
     * value cells at a font size of its own, for a user who finds the badge too small to read.
     * Each field is a switch, and the icons are what let the order be fixed: a cell names itself,
     * so the user never has to remember which number came third.
     */
    public static final class GnssInfoBrickPrefs extends TableBrickPrefs {
        /** The satellite count. What it counts is decided by {@link #satellitesUsedInFix}. */
        public final Bool showSatellites;
        /**
         * Prefix the satellite total with the number USED IN THE FIX, e.g. {@code 9/12}. Both
         * numbers come from the same sender, so they can be compared; a sender that does not
         * report the used count leaves the plain total showing.
         */
        public final Bool satellitesUsedInFix;
        /** The dead-reckoning and substitution lamps, and the colour they wash over the count. */
        public final Bool showMode;
        /** The lamp that lights while the position is anchored to a road. */
        public final Bool showRoad;
        /** Horizontal accuracy of the last fix. */
        public final Bool showAccuracy;
        public final Bool showSpeed;
        /** Altitude — ellipsoidal, see {@code GnssInfoRenderBrick#altitudeValue}. */
        public final Bool showAltitude;
        /** How long ago the last fix arrived. */
        public final Bool showFixAge;
        public GnssInfoBrickPrefs(Preferences p) {
            super(p, "gnssInfo", 40);
            showSatellites = new Bool(p, "gnssInfoShowSatellites", true);
            satellitesUsedInFix = new Bool(p, "gnssInfoSatellitesUsedInFix", false);
            showMode = new Bool(p, "gnssInfoShowMode", true);
            showRoad = new Bool(p, "gnssInfoShowRoad", true);
            showAccuracy = new Bool(p, "gnssInfoShowAccuracy", false);
            showSpeed = new Bool(p, "gnssInfoShowSpeed", false);
            showAltitude = new Bool(p, "gnssInfoShowAltitude", false);
            showFixAge = new Bool(p, "gnssInfoShowFixAge", false);
        }
    }

    /** Bluetooth brick adds the connected-device-count badge toggle. */
    public static final class BluetoothBrickPrefs extends IconBrickPrefs {
        public final Bool showDeviceCountBadge;

        public BluetoothBrickPrefs(Preferences p) {
            super(p, "bluetooth");
            showDeviceCountBadge = new Bool(p, "bluetoothShowDeviceCountBadge", true);
        }
    }

    private final SharedPreferences prefs;

    // Global widget settings.
    public final Bool widgetEnabled = new Bool(this, "enabled", false);
    public final Bool widgetAlignRight = new Bool(this, "widgetAlignRight", false);
    /** 0 = floating overlay (current behaviour), 1 = full-width status bar at the top. */
    public final Int widgetMode = new Int(this, "widgetMode", 0);
    public final Int iconDesign = new Int(this, "iconDesign", 0);
    public final Int iconStyle = new Int(this, "iconStyle", 0);
    /** 0 = follow system, 1 = always light, 2 = always dark, 3 = inverse of system. */
    public final Int widgetTheme = new Int(this, "widgetTheme", 0);
    public final Int backgroundAlpha = new Int(this, "backgroundAlpha", 0xAA);
    public final Int backgroundCornerRadius = new Int(this, "backgroundCornerRadius", 100);
    public final Int overlayX = new Int(this, "overlayX", 200);
    public final Int overlayY = new Int(this, "overlayY", 300);
    /** Padding inside the widget container on each side, in px. */
    public final Int paddingLeft = new Int(this, "paddingLeft", 40);
    public final Int paddingTop = new Int(this, "paddingTop", 0);
    public final Int paddingRight = new Int(this, "paddingRight", 40);
    public final Int paddingBottom = new Int(this, "paddingBottom", 0);
    public final StringSet hideInPackages = new StringSet(this, "hideInPackages");

    /** Comma-separated list of brick types in display order. Missing types are hidden. */
    public final Str brickOrder = new Str(this, "brickOrder", "TIME,DATE,WIFI,GPS");

    // Per-brick settings.
    public final TextBrickPrefs time = new TextBrickPrefs(this, "time", 60);
    public final DateBrickPrefs date = new DateBrickPrefs(this);
    public final MediaBrickPrefs media = new MediaBrickPrefs(this);
    public final IconBrickPrefs wifi = new IconBrickPrefs(this, "wifi");
    public final GpsBrickPrefs gps = new GpsBrickPrefs(this);
    public final BluetoothBrickPrefs bluetooth = new BluetoothBrickPrefs(this);
    public final GnssInfoBrickPrefs gnssInfo = new GnssInfoBrickPrefs(this);
    // Car-specific temperature bricks (fed by the flavor's CarIntegration).
    public final TempBrickPrefs indoorTemp = new TempBrickPrefs(this, "indoorTemp");
    public final TempBrickPrefs outdoorTemp = new TempBrickPrefs(this, "outdoorTemp");

    /**
     * The single brick → settings registry. Every {@link BrickType} must be present; adding a
     * brick means adding its prefs field above and one line here, and nothing else in this class.
     */
    private final EnumMap<BrickType, BrickPrefs> brickPrefsByType = new EnumMap<>(BrickType.class);

    {
        brickPrefsByType.put(BrickType.TIME, time);
        brickPrefsByType.put(BrickType.DATE, date);
        brickPrefsByType.put(BrickType.MEDIA, media);
        brickPrefsByType.put(BrickType.WIFI, wifi);
        brickPrefsByType.put(BrickType.GPS, gps);
        brickPrefsByType.put(BrickType.BLUETOOTH, bluetooth);
        brickPrefsByType.put(BrickType.GNSS_INFO, gnssInfo);
        brickPrefsByType.put(BrickType.INDOOR_TEMP, indoorTemp);
        brickPrefsByType.put(BrickType.OUTDOOR_TEMP, outdoorTemp);
        if (brickPrefsByType.size() != BrickType.values().length) {
            // Fail at construction rather than on the one screen that happens to touch the new
            // brick — a missing entry is a compile-time-invisible omission.
            throw new IllegalStateException("Every BrickType needs a BrickPrefs registration");
        }
        for (BrickPrefs a : brickPrefsByType.values()) {
            for (BrickPrefs b : brickPrefsByType.values()) {
                if (a != b && b.prefix.startsWith(a.prefix)) {
                    // resetBrick() wipes by prefix, so this would make one brick's reset clear
                    // another brick's settings.
                    throw new IllegalStateException(
                            "Brick prefix '" + a.prefix + "' shadows '" + b.prefix + "'");
                }
            }
        }
    }

    /** Settings of a brick. Never {@code null} — every {@link BrickType} is registered above. */
    @NonNull
    public BrickPrefs brickPrefs(BrickType type) {
        BrickPrefs p = brickPrefsByType.get(type);
        if (p == null) throw new IllegalArgumentException("Unknown brick type: " + type);
        return p;
    }

    public StringSet hideListFor(BrickType type) {
        return brickPrefs(type).hideInPackages;
    }

    public Int statusAlignmentFor(BrickType type) {
        return brickPrefs(type).statusAlignment;
    }

    public Str hideSourceFor(BrickType type) {
        return brickPrefs(type).hideSource;
    }

    /** Per-brick INVISIBLE-vs-GONE toggle for foreground-app hiding. */
    public Bool hideKeepsSpaceFor(BrickType type) {
        return brickPrefs(type).hideKeepsSpace;
    }

    public String hideListKeyFor(BrickType type) {
        return brickPrefs(type).hideInPackages.key();
    }

    /**
     * Returns the brick whose hide-in-apps list this brick uses. {@code type} itself if the
     * brick has its own list; another type if it inherits.
     */
    public BrickType effectiveHideSourceFor(BrickType type) {
        String src = hideSourceFor(type).get();
        BrickType resolved = BrickType.fromName(src);
        if (resolved != null && resolved != type) {
            return resolved;
        }
        return type;
    }

    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        migrateLegacyPrefsIfNeeded();
    }

    /**
     * Wipes all stored preferences. Defaults take over on next read.
     * Uses {@link android.content.SharedPreferences.Editor#commit()} (synchronous) instead of
     * {@code apply()} because the caller typically kills the process immediately afterwards and
     * an async write may not reach disk in time.
     */
    public void resetAll() {
        prefs.edit().clear().commit();
    }

    /**
     * Removes every stored pref whose storage key starts with the given brick's prefix. After
     * removal subsequent reads return the brick's defaults. The brick stays in {@link #brickOrder}
     * — only its own settings (font, outline, margins, hide list, alignment, type-specific flags)
     * are reset.
     */
    public void resetBrick(BrickType type) {
        String prefix = brickPrefs(type).prefix;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /**
     * One-shot migration from the pre-brick layout. Idempotent: re-running after the migration is
     * a no-op (detected by the presence of the {@code brickOrder} key). Also re-run after every
     * settings import in case the imported file used the legacy schema.
     */
    private void migrateLegacyPrefsIfNeeded() {
        if (prefs.contains("brickOrder")) return;
        if (!prefs.contains("showWifiIcon") && !prefs.contains("showGnssIcon")
                && !prefs.contains("showTime") && !prefs.contains("showDate")
                && !prefs.contains("showMedia")) {
            // Fresh install — keep the default brickOrder; nothing to migrate.
            return;
        }

        SharedPreferences.Editor e = prefs.edit();

        StringBuilder order = new StringBuilder();
        if (prefs.getBoolean("showTime", false)) appendOrder(order, BrickType.TIME);
        if (prefs.getBoolean("showDate", false) || prefs.getBoolean("showDayOfTheWeek", false)) {
            appendOrder(order, BrickType.DATE);
        }
        if (prefs.getBoolean("showMedia", false)) appendOrder(order, BrickType.MEDIA);
        if (prefs.getBoolean("showWifiIcon", true)) appendOrder(order, BrickType.WIFI);
        if (prefs.getBoolean("showGnssIcon", true)) appendOrder(order, BrickType.GPS);
        e.putString("brickOrder", order.toString());

        // Carry over the date sub-toggles into the new namespace.
        e.putBoolean("dateShowDate", prefs.getBoolean("showDate", true));
        e.putBoolean("dateShowDayOfWeek", prefs.getBoolean("showDayOfTheWeek", true));
        e.putBoolean("dateShowFullName", prefs.getBoolean("showFullDayAndMonth", false));
        e.putBoolean("dateOneLineLayout", prefs.getBoolean("oneLineLayout", false));
        // dateBeforeDayOfWeek and dateAlignment kept their original keys.

        // Text outline → per-text-brick.
        int textAlpha = prefs.getInt("textOutlineAlpha", 0xAA);
        int textWidth = prefs.getInt("textOutlineWidth", 2);
        e.putInt("timeOutlineAlpha", textAlpha);
        e.putInt("timeOutlineWidth", textWidth);
        e.putInt("dateOutlineAlpha", textAlpha);
        e.putInt("dateOutlineWidth", textWidth);
        e.putInt("mediaOutlineAlpha", textAlpha);
        e.putInt("mediaOutlineWidth", textWidth);

        // Icon outline + size → per-icon-brick.
        int iconAlpha = prefs.getInt("iconOutlineAlpha", 0xAA);
        int iconWidth = prefs.getInt("iconOutlineWidth", 2);
        int iconSize = prefs.getInt("iconSize", 70);
        e.putInt("wifiOutlineAlpha", iconAlpha);
        e.putInt("wifiOutlineWidth", iconWidth);
        e.putInt("wifiSize", iconSize);
        e.putInt("gpsOutlineAlpha", iconAlpha);
        e.putInt("gpsOutlineWidth", iconWidth);
        e.putInt("gpsSize", iconSize);

        // Per-text adjust Y kept original keys: timeAdjustY, dateAdjustY → migrate from legacy.
        e.putInt("timeAdjustY", prefs.getInt("adjustTimeY", 0));
        e.putInt("dateAdjustY", prefs.getInt("adjustDateY", 0));

        // Legacy spacings: spacingLeftOfMedia → media.marginStart; spacingLeftOfIcons → wifi.marginStart.
        e.putInt("mediaMarginStart", prefs.getInt("spacingBetweenTextsAndIcons", 0));
        e.putInt("wifiMarginStart", prefs.getInt("spacingBetweenMediaAndIcons", 0));

        // Carry the satellite badge toggle.
        e.putBoolean("gpsShowSatelliteBadge", prefs.getBoolean("showGnssSatelliteBadge", true));

        e.apply();
    }

    private static void appendOrder(StringBuilder sb, BrickType type) {
        if (sb.length() > 0) sb.append(',');
        sb.append(type.name());
    }

    private static final String EXPORT_FILE_TYPE = "dezz.status.widget.settings";
    private static final int EXPORT_FILE_VERSION = 1;
    private static final String KEY_FILE_TYPE = "fileType";
    private static final String KEY_FILE_VERSION = "fileVersion";
    private static final String KEY_PRESET_NAME = "presetName";
    private static final String KEY_PREFERENCES = "preferences";

    /**
     * Extracts the optional {@code presetName} field from a preset/settings JSON. Returns
     * {@code null} if the field is absent or the JSON is malformed.
     */
    @Nullable
    public static String readPresetName(@NonNull String json) {
        try {
            JSONObject root = new JSONObject(json);
            String name = root.optString(KEY_PRESET_NAME, "").trim();
            return name.isEmpty() ? null : name;
        } catch (JSONException e) {
            return null;
        }
    }

    public static class InvalidSettingsFileException extends Exception {
        public InvalidSettingsFileException(String message) {
            super(message);
        }
    }

    public String exportToJson() throws JSONException {
        return exportToJson(null);
    }

    /**
     * Same as {@link #exportToJson()} but writes the optional {@code presetName} field. Used when
     * saving the current state as a named user preset.
     */
    public String exportToJson(@Nullable String presetName) throws JSONException {
        JSONObject preferencesNode = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    array.put(String.valueOf(item));
                }
                preferencesNode.put(entry.getKey(), array);
            } else {
                preferencesNode.put(entry.getKey(), value);
            }
        }
        JSONObject root = new JSONObject();
        root.put(KEY_FILE_TYPE, EXPORT_FILE_TYPE);
        root.put(KEY_FILE_VERSION, EXPORT_FILE_VERSION);
        if (presetName != null && !presetName.trim().isEmpty()) {
            root.put(KEY_PRESET_NAME, presetName.trim());
        }
        root.put(KEY_PREFERENCES, preferencesNode);
        return root.toString(2);
    }

    public void importFromJson(String json) throws JSONException, InvalidSettingsFileException {
        JSONObject root = new JSONObject(json);
        if (!EXPORT_FILE_TYPE.equals(root.optString(KEY_FILE_TYPE, null))) {
            throw new InvalidSettingsFileException("Not a Status Widget settings file");
        }
        int version = root.optInt(KEY_FILE_VERSION, -1);
        if (version <= 0 || version > EXPORT_FILE_VERSION) {
            throw new InvalidSettingsFileException("Unsupported settings file version: " + version);
        }
        JSONObject preferencesNode = root.optJSONObject(KEY_PREFERENCES);
        if (preferencesNode == null) {
            throw new InvalidSettingsFileException("Missing preferences section");
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        Iterator<String> keys = preferencesNode.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = preferencesNode.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                long longValue = (Long) value;
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    editor.putInt(key, (int) longValue);
                } else {
                    editor.putLong(key, longValue);
                }
            } else if (value instanceof Double || value instanceof Float) {
                editor.putFloat(key, ((Number) value).floatValue());
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.getString(i));
                }
                editor.putStringSet(key, set);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        editor.apply();
        // The file may be from the legacy (pre-brick) schema — re-run migration so it adapts.
        migrateLegacyPrefsIfNeeded();
    }
}
