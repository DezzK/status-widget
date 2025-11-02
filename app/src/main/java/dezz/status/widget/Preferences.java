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
    private static final String PREF_TIME_FONT_SIZE = "timeFontSize";
    private static final String PREF_DATE_FONT_SIZE = "dateFontSize";
    private static final String PREF_SHOW_DATE = "showDate";
    private static final String PREF_SHOW_TIME = "showTime";
    private static final String PREF_SHOW_DAY_OF_THE_WEEK = "showDayOfTheWeek";
    private static final String PREF_ONE_LINE_LAYOUT = "oneLineLayout";
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

    public static boolean showDayOfTheWeek(Context context) {
        return getPrefs(context).getBoolean(PREF_SHOW_DAY_OF_THE_WEEK, false);
    }

    public static void saveShowDayOfTheWeek(Context context, boolean value) {
        setBoolean(context, PREF_SHOW_DAY_OF_THE_WEEK, value);
    }

    public static boolean oneLineLayout(Context context) {
        return getPrefs(context).getBoolean(PREF_ONE_LINE_LAYOUT, false);
    }

    public static void saveOneLineLayout(Context context, boolean value) {
        setBoolean(context, PREF_ONE_LINE_LAYOUT, value);
    }

    public static int iconSize(Context context) {
        return getPrefs(context).getInt(PREF_ICON_SIZE, 70);
    }

    public static void saveIconSize(Context context, int value) {
        getPrefs(context).edit().putInt(PREF_ICON_SIZE, value).apply();
    }

    public static int timeFontSize(Context context) {
        return getPrefs(context).getInt(PREF_TIME_FONT_SIZE, 60);
    }

    public static void saveTimeFontSize(Context context, int value) {
        getPrefs(context).edit().putInt(PREF_TIME_FONT_SIZE, value).apply();
    }

    public static int dateFontSize(Context context) {
        return getPrefs(context).getInt(PREF_DATE_FONT_SIZE, 20);
    }

    public static void saveDateFontSize(Context context, int value) {
        getPrefs(context).edit().putInt(PREF_DATE_FONT_SIZE, value).apply();
    }

    public static Point overlayPosition(Context context) {
        SharedPreferences prefs = getPrefs(context);
        int x = prefs.getInt(PREF_OVERLAY_X, 0);
        int y = prefs.getInt(PREF_OVERLAY_Y, 0);
        return new Point(x, y);
    }

    public static void saveOverlayPosition(Context context, int x, int y) {
        getPrefs(context).edit().putInt(PREF_OVERLAY_X, x).putInt(PREF_OVERLAY_Y, y).apply();
    }

    public static void resetOverlayPosition(Context context) {
        getPrefs(context).edit().remove(PREF_OVERLAY_X).remove(PREF_OVERLAY_Y).apply();
    }


    private static void setBoolean(Context context, String key, boolean value) {
        getPrefs(context).edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
