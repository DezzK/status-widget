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
    private static final String PREF_USE_COLOR_ICONS = "useColorIcons";
    private static final String PREF_SHOW_DATE = "showDate";
    private static final String PREF_SHOW_TIME = "showTime";
    private static final String PREF_SHOW_DAY_OF_THE_WEEK = "showDayOfTheWeek";
    private static final String PREF_SHOW_WIFI_ICON = "showWifiIcon";
    private static final String PREF_SHOW_GNSS_ICON = "showGnssIcon";
    private static final String PREF_SHOW_FULL_DAY_AND_MONTH = "showFullDayAndMonth";
    private static final String PREF_ONE_LINE_LAYOUT = "oneLineLayout";
    private static final String PREF_ICON_SIZE = "iconSize";
    private static final String PREF_TIME_FONT_SIZE = "timeFontSize";
    private static final String PREF_DATE_FONT_SIZE = "dateFontSize";
    private static final String PREF_SPACING_BETWEEN_TEXTS_AND_ICONS = "spacingBetweenTextsAndIcons";
    private static final String PREF_ADJUST_TIME_Y = "adjustTimeY";
    private static final String PREF_ADJUST_DATE_Y = "adjustDateY";
    private static final String PREF_OVERLAY_X = "overlayX";
    private static final String PREF_OVERLAY_Y = "overlayY";
    
    private final SharedPreferences prefs;
    
    public Preferences(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public boolean widgetEnabled() {
        return prefs.getBoolean(PREF_WIDGET_ENABLED, false);
    }

    public void saveWidgetEnabled(boolean value) {
        setBoolean(PREF_WIDGET_ENABLED, value);
    }

    public boolean useColorIcons() {
        return prefs.getBoolean(PREF_USE_COLOR_ICONS, false);
    }

    public void saveUseColorIcons(boolean value) {
        setBoolean(PREF_USE_COLOR_ICONS, value);
    }

    public boolean showDate() {
        return prefs.getBoolean(PREF_SHOW_DATE, false);
    }

    public void saveShowDate(boolean value) {
        setBoolean(PREF_SHOW_DATE, value);
    }

    public boolean showTime() {
        return prefs.getBoolean(PREF_SHOW_TIME, false);
    }

    public void saveShowTime(boolean value) {
        setBoolean(PREF_SHOW_TIME, value);
    }

    public boolean showDayOfTheWeek() {
        return prefs.getBoolean(PREF_SHOW_DAY_OF_THE_WEEK, false);
    }

    public void saveShowDayOfTheWeek(boolean value) {
        setBoolean(PREF_SHOW_DAY_OF_THE_WEEK, value);
    }

    public boolean showWifiIcon() {
        return prefs.getBoolean(PREF_SHOW_WIFI_ICON, true);
    }

    public void saveShowWifiIcon(boolean value) {
        setBoolean(PREF_SHOW_WIFI_ICON, value);
    }

    public boolean showGnssIcon() {
        return prefs.getBoolean(PREF_SHOW_GNSS_ICON, true);
    }

    public void saveShowGnssIcon(boolean value) {
        setBoolean(PREF_SHOW_GNSS_ICON, value);
    }

    public boolean showFullDayAndMonth() {
        return prefs.getBoolean(PREF_SHOW_FULL_DAY_AND_MONTH, false);
    }

    public void saveShowFullDayAndMonth(boolean value) {
        setBoolean(PREF_SHOW_FULL_DAY_AND_MONTH, value);
    }

    public boolean oneLineLayout() {
        return prefs.getBoolean(PREF_ONE_LINE_LAYOUT, false);
    }

    public void saveOneLineLayout(boolean value) {
        setBoolean(PREF_ONE_LINE_LAYOUT, value);
    }

    public int iconSize() {
        return prefs.getInt(PREF_ICON_SIZE, 70);
    }

    public void saveIconSize(int value) {
        setInt(PREF_ICON_SIZE, value);
    }

    public int timeFontSize() {
        return prefs.getInt(PREF_TIME_FONT_SIZE, 60);
    }

    public void saveTimeFontSize(int value) {
        setInt(PREF_TIME_FONT_SIZE, value);
    }

    public int dateFontSize() {
        return prefs.getInt(PREF_DATE_FONT_SIZE, 20);
    }

    public void saveDateFontSize(int value) {
        setInt(PREF_DATE_FONT_SIZE, value);
    }

    public int spacingBetweenTextsAndIcons() {
        return prefs.getInt(PREF_SPACING_BETWEEN_TEXTS_AND_ICONS, 0);
    }

    public void saveSpacingBetweenTextsAndIcons(int value) {
        setInt(PREF_SPACING_BETWEEN_TEXTS_AND_ICONS, value);
    }

    public int adjustTimeY() {
        return prefs.getInt(PREF_ADJUST_TIME_Y, 0);
    }

    public void saveAdjustTimeY(int value) {
        setInt(PREF_ADJUST_TIME_Y, value);
    }

    public int adjustDateY() {
        return prefs.getInt(PREF_ADJUST_DATE_Y, 0);
    }

    public void saveAdjustDateY(int value) {
        setInt(PREF_ADJUST_DATE_Y, value);
    }

    public Point overlayPosition() {
        int x = prefs.getInt(PREF_OVERLAY_X, 0);
        int y = prefs.getInt(PREF_OVERLAY_Y, 0);
        return new Point(x, y);
    }

    public void saveOverlayPosition(int x, int y) {
        prefs.edit().putInt(PREF_OVERLAY_X, x).putInt(PREF_OVERLAY_Y, y).apply();
    }

    public void resetOverlayPosition() {
        prefs.edit().remove(PREF_OVERLAY_X).remove(PREF_OVERLAY_Y).apply();
    }

    private void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    private void setInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }
}
