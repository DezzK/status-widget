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
import android.graphics.Point;

public class Preferences {
    public static final class BooleanPreference {
        private final Preferences preferences;
        private final String key;
        private final boolean defaultValue;

        public BooleanPreference(Preferences preferences, String key, boolean defaultValue) {
            this.preferences = preferences;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            return preferences.prefs.getBoolean(key, defaultValue);
        }

        public void set(boolean value) {
            preferences.prefs.edit().putBoolean(key, value).apply();
        }
    }

    public static final class IntPreference {
        private final Preferences preferences;
        private final String key;
        private final int defaultValue;

        public IntPreference(Preferences preferences, String key, int defaultValue) {
            this.preferences = preferences;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public int get() {
            return preferences.prefs.getInt(key, defaultValue);
        }

        public void set(int value) {
            preferences.prefs.edit().putInt(key, value).apply();
        }
    }

    public static final class PointPreference {
        private final Preferences preferences;
        private final String keyX;
        private final String keyY;

        public PointPreference(Preferences preferences, String keyX, String keyY) {
            this.preferences = preferences;
            this.keyX = keyX;
            this.keyY = keyY;
        }

        public Point get() {
            int x = preferences.prefs.getInt(keyX, 0);
            int y = preferences.prefs.getInt(keyY, 0);
            return new Point(x, y);
        }

        public void set(int x, int y) {
            preferences.prefs.edit().putInt(keyX, x).putInt(keyY, y).apply();
        }

        public void reset() {
            preferences.prefs.edit().remove(keyX).remove(keyY).apply();
        }
    }

    public final BooleanPreference widgetEnabled = new BooleanPreference(this, "enabled", false);
    public final BooleanPreference useColorIcons = new BooleanPreference(this, "useColorIcons", false);
    public final BooleanPreference showDate = new BooleanPreference(this, "showDate", false);
    public final BooleanPreference showTime = new BooleanPreference(this, "showTime", false);
    public final BooleanPreference showDayOfTheWeek = new BooleanPreference(this, "showDayOfTheWeek", false);
    public final BooleanPreference showWifiIcon = new BooleanPreference(this, "showWifiIcon", true);
    public final BooleanPreference showGnssIcon = new BooleanPreference(this, "showGnssIcon", true);
    public final BooleanPreference showFullDayAndMonth = new BooleanPreference(this, "showFullDayAndMonth", false);
    public final BooleanPreference oneLineLayout = new BooleanPreference(this, "oneLineLayout", false);
    public final IntPreference iconSize = new IntPreference(this, "iconSize", 70);
    public final IntPreference timeFontSize = new IntPreference(this, "timeFontSize", 60);
    public final IntPreference dateFontSize = new IntPreference(this, "dateFontSize", 20);
    public final IntPreference spacingBetweenTextsAndIcons = new IntPreference(this, "spacingBetweenTextsAndIcons", 0);
    public final IntPreference adjustTimeY = new IntPreference(this, "adjustTimeY", 0);
    public final IntPreference adjustDateY = new IntPreference(this, "adjustDateY", 0);
    public final PointPreference overlayPosition = new PointPreference(this, "overlayX", "overlayY");

    private final SharedPreferences prefs;
    
    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
