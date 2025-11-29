/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
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
    public final Int iconStyle = new Int(this, "iconStyle", 0);
    public final Bool showDate = new Bool(this, "showDate", false);
    public final Bool showTime = new Bool(this, "showTime", false);
    public final Bool showDayOfTheWeek = new Bool(this, "showDayOfTheWeek", false);
    public final Int calendarAlignment = new Int(this, "calendarAlignment", 0);
    public final Bool showWifiIcon = new Bool(this, "showWifiIcon", true);
    public final Bool showGnssIcon = new Bool(this, "showGnssIcon", true);
    public final Bool showFullDayAndMonth = new Bool(this, "showFullDayAndMonth", false);
    public final Bool oneLineLayout = new Bool(this, "oneLineLayout", false);
    public final Int iconSize = new Int(this, "iconSize", 70);
    public final Int timeFontSize = new Int(this, "timeFontSize", 60);
    public final Int dateFontSize = new Int(this, "dateFontSize", 20);
    public final Int spacingBetweenTextsAndIcons = new Int(this, "spacingBetweenTextsAndIcons", 0);
    public final Int adjustTimeY = new Int(this, "adjustTimeY", 0);
    public final Int adjustDateY = new Int(this, "adjustDateY", 0);
    public final Int overlayX = new Int(this, "overlayX", 0);
    public final Int overlayY = new Int(this, "overlayY", 0);

    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
