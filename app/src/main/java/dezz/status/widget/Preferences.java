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
    private static final String PREF_WIDGET_ENABLED = "enabled";
    private static final String PREF_ICON_SIZE = "iconSize";
    private static final String PREF_FONT_SIZE = "fontSize";
    private static final String PREF_SHOW_DATE = "showDate";
    private static final String PREF_SHOW_TIME = "showTime";
    private static final String PREF_OVERLAY_X = "overlayX";
    private static final String PREF_OVERLAY_Y = "overlayY";

    public static boolean widgetEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_WIDGET_ENABLED, false);
    }

    public static void saveWidgetEnabled(Context context, boolean value) {
        setBoolean(context, PREF_WIDGET_ENABLED, value);
    }

    public static boolean showDate(Context context) {
        return getPrefs(context).getBoolean(PREF_SHOW_DATE, false);
    }

    public static void saveShowDate(Context context, boolean value) {
        setBoolean(context, PREF_SHOW_DATE, value);
    }

    public static boolean showTime(Context context) {
        return getPrefs(context).getBoolean(PREF_SHOW_TIME, false);
    }

    public static void saveShowTime(Context context, boolean value) {
        setBoolean(context, PREF_SHOW_TIME, value);
    }

    public static int iconSize(Context context) {
        return getPrefs(context).getInt(PREF_ICON_SIZE, 50);
    }

    public static void saveIconSize(Context context, int value) {
        getPrefs(context).edit().putInt(PREF_ICON_SIZE, value).apply();
    }

    public static int fontSize(Context context) {
        return getPrefs(context).getInt(PREF_FONT_SIZE, 36);
    }

    public static void saveFontSize(Context context, int value) {
        getPrefs(context).edit().putInt(PREF_FONT_SIZE, value).apply();
    }

    public static Point overlayPosition(Context context) {
        SharedPreferences prefs = getPrefs(context);
        int x = prefs.getInt(PREF_OVERLAY_X, 0);
        int y = prefs.getInt(PREF_OVERLAY_Y, 100);
        return new Point(x, y);
    }

    public static void saveOverlayPosition(Context context, int x, int y) {
        getPrefs(context).edit().putInt(PREF_OVERLAY_X, x).putInt(PREF_OVERLAY_Y, y).apply();
    }

    private static void setBoolean(Context context, String key, boolean value) {
        getPrefs(context).edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
