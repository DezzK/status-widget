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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Date and day of week. Its format string is assembled from five preferences, so the brick keeps
 * the last pattern it built and only rebuilds the formatter when that pattern actually changes.
 */
final class DateRenderBrick extends TextRenderBrick {

    private final Preferences.DateBrickPrefs datePrefs;

    @Nullable
    private SimpleDateFormat format;
    @Nullable
    private String currentPattern;

    DateRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.DATE, host.prefs().date, R.id.dateText);
        this.datePrefs = host.prefs().date;
    }

    /** Both halves can be switched off; with neither there is nothing to draw. */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type)
                && (datePrefs.showDate.get() || datePrefs.showDayOfWeek.get());
    }

    @Override
    protected int lineCount() {
        return twoLines() ? 2 : 1;
    }

    @Override
    boolean needsClockTick() {
        return true;
    }

    @Override
    void onClockTick(@NonNull Date now) {
        boolean showDate = datePrefs.showDate.get();
        boolean showDayOfWeek = datePrefs.showDayOfWeek.get();
        if (!showDate && !showDayOfWeek) return;
        setTextIfChanged(formatter(showDate, showDayOfWeek).format(now));
    }

    @Override
    void onConfigurationChanged() {
        // Force a rebuild against the new locale on the next tick.
        currentPattern = null;
    }

    private boolean twoLines() {
        return datePrefs.showDate.get() && datePrefs.showDayOfWeek.get()
                && !datePrefs.oneLineLayout.get();
    }

    private SimpleDateFormat formatter(boolean showDate, boolean showDayOfWeek) {
        boolean fullNames = datePrefs.showFullName.get();
        boolean both = showDate && showDayOfWeek;
        String divider = both ? (datePrefs.oneLineLayout.get() ? "," : " \n") : "";
        // Leading/trailing spaces keep the outline from being cropped by the canvas.
        String dayPart = showDayOfWeek ? (fullNames ? " EEEE" : " EEE") : "";
        String datePart = showDate ? (fullNames ? " d MMMM" : " d MMM") : "";
        String pattern = datePrefs.dateBeforeDayOfWeek.get()
                ? datePart + divider + dayPart + " "
                : dayPart + divider + datePart + " ";
        if (format == null || !pattern.equals(currentPattern)) {
            format = new SimpleDateFormat(pattern, Locale.getDefault());
            currentPattern = pattern;
        }
        return format;
    }
}
