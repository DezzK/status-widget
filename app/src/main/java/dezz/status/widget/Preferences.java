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

    private final SharedPreferences prefs;

    public final Bool widgetEnabled = new Bool(this, "enabled", false);
    public final Bool widgetAlignRight = new Bool(this, "widgetAlignRight", false);
    public final Int iconDesign = new Int(this, "iconDesign", 0);
    public final Int iconStyle = new Int(this, "iconStyle", 0);
    public final Bool showDate = new Bool(this, "showDate", false);
    public final Bool showTime = new Bool(this, "showTime", false);
    public final Bool showDayOfTheWeek = new Bool(this, "showDayOfTheWeek", false);
    public final Bool dateBeforeDayOfWeek = new Bool(this, "dateBeforeDayOfWeek", false);
    public final Int calendarAlignment = new Int(this, "calendarAlignment", 0);
    public final Bool showWifiIcon = new Bool(this, "showWifiIcon", true);
    public final Bool showGnssIcon = new Bool(this, "showGnssIcon", true);
    public final Bool showGnssSatelliteBadge = new Bool(this, "showGnssSatelliteBadge", true);
    public final Bool showFullDayAndMonth = new Bool(this, "showFullDayAndMonth", false);
    public final Bool oneLineLayout = new Bool(this, "oneLineLayout", false);
    public final Int iconSize = new Int(this, "iconSize", 70);
    public final Int timeFontSize = new Int(this, "timeFontSize", 60);
    public final Int dateFontSize = new Int(this, "dateFontSize", 20);
    public final Int backgroundAlpha = new Int(this, "backgroundAlpha", 0xAA);
    public final Int backgroundCornerRadius = new Int(this, "backgroundCornerRadius", 100);
    public final Int textOutlineAlpha = new Int(this, "textOutlineAlpha", 0xAA);
    public final Int iconOutlineAlpha = new Int(this, "iconOutlineAlpha", 0xAA);
    public final Int textOutlineWidth = new Int(this, "textOutlineWidth", 2);
    public final Int iconOutlineWidth = new Int(this, "iconOutlineWidth", 2);
    public final Int spacingBetweenTextsAndIcons = new Int(this, "spacingBetweenTextsAndIcons", 0);
    public final Int adjustTimeY = new Int(this, "adjustTimeY", 0);
    public final Int adjustDateY = new Int(this, "adjustDateY", 0);
    public final Int overlayX = new Int(this, "overlayX", 200);
    public final Int overlayY = new Int(this, "overlayY", 300);
    public final StringSet hideInPackages = new StringSet(this, "hideInPackages");

    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
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
    }
}
