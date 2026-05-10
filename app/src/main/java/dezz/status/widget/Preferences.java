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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
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

    /** Common settings for any text-based brick. */
    public static class TextBrickPrefs {
        public final Int fontSize;
        public final Int outlineAlpha;
        public final Int outlineWidth;
        public final Int marginStart;
        public final Int marginEnd;

        public TextBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            fontSize = new Int(p, prefix + "FontSize", defaultFontSize);
            outlineAlpha = new Int(p, prefix + "OutlineAlpha", 0xAA);
            outlineWidth = new Int(p, prefix + "OutlineWidth", 2);
            marginStart = new Int(p, prefix + "MarginStart", 0);
            marginEnd = new Int(p, prefix + "MarginEnd", 0);
        }
    }

    /** Single-line text brick (Time, Date) — adds vertical adjust. */
    public static class SingleLineTextBrickPrefs extends TextBrickPrefs {
        public final Int adjustY;

        public SingleLineTextBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            super(p, prefix, defaultFontSize);
            adjustY = new Int(p, prefix + "AdjustY", 0);
        }
    }

    /** Date brick — date number, day of week, formatting and ordering options. */
    public static final class DateBrickPrefs extends SingleLineTextBrickPrefs {
        public final Bool showDate;
        public final Bool showDayOfWeek;
        public final Bool showFullName;
        public final Bool dateBeforeDayOfWeek;
        public final Bool oneLineLayout;
        public final Int alignment;

        public DateBrickPrefs(Preferences p) {
            super(p, "date", 20);
            showDate = new Bool(p, "dateShowDate", true);
            showDayOfWeek = new Bool(p, "dateShowDayOfWeek", true);
            showFullName = new Bool(p, "dateShowFullName", false);
            dateBeforeDayOfWeek = new Bool(p, "dateBeforeDayOfWeek", false);
            oneLineLayout = new Bool(p, "dateOneLineLayout", false);
            alignment = new Int(p, "dateAlignment", 0);
        }
    }

    /** Common settings for an icon brick. */
    public static class IconBrickPrefs {
        public final Int size;
        public final Int outlineAlpha;
        public final Int outlineWidth;
        public final Int marginStart;
        public final Int marginEnd;

        public IconBrickPrefs(Preferences p, String prefix) {
            size = new Int(p, prefix + "Size", 70);
            outlineAlpha = new Int(p, prefix + "OutlineAlpha", 0xAA);
            outlineWidth = new Int(p, prefix + "OutlineWidth", 2);
            marginStart = new Int(p, prefix + "MarginStart", 0);
            marginEnd = new Int(p, prefix + "MarginEnd", 0);
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

    private final SharedPreferences prefs;

    // Global widget settings.
    public final Bool widgetEnabled = new Bool(this, "enabled", false);
    public final Bool widgetAlignRight = new Bool(this, "widgetAlignRight", false);
    public final Int iconDesign = new Int(this, "iconDesign", 0);
    public final Int iconStyle = new Int(this, "iconStyle", 0);
    // 0 = follow system, 1 = always light, 2 = always dark.
    public final Int widgetTheme = new Int(this, "widgetTheme", 0);
    public final Int backgroundAlpha = new Int(this, "backgroundAlpha", 0xAA);
    public final Int backgroundCornerRadius = new Int(this, "backgroundCornerRadius", 100);
    public final Int overlayX = new Int(this, "overlayX", 200);
    public final Int overlayY = new Int(this, "overlayY", 300);
    public final StringSet hideInPackages = new StringSet(this, "hideInPackages");

    // Layout: the comma-separated list of brick types in display order. Missing types are hidden.
    public final Str brickOrder = new Str(this, "brickOrder", "TIME,DATE,WIFI,GPS");

    // Whether the user has been shown the notification access prompt at least once. Used to keep
    // the media brick "active" only when the user has explicitly granted access.
    public final Bool mediaEnabled = new Bool(this, "mediaEnabled", false);

    // Per-brick settings.
    public final SingleLineTextBrickPrefs time = new SingleLineTextBrickPrefs(this, "time", 60);
    public final DateBrickPrefs date = new DateBrickPrefs(this);
    public final TextBrickPrefs media = new TextBrickPrefs(this, "media", 20);
    public final IconBrickPrefs wifi = new IconBrickPrefs(this, "wifi");
    public final GpsBrickPrefs gps = new GpsBrickPrefs(this);

    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        migrateLegacyPrefsIfNeeded();
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

        e.putBoolean("mediaEnabled", prefs.getBoolean("showMedia", false));

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
    private static final String KEY_PREFERENCES = "preferences";

    public static class InvalidSettingsFileException extends Exception {
        public InvalidSettingsFileException(String message) {
            super(message);
        }
    }

    public String exportToJson() throws JSONException {
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
