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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The clock. Redrawn on the shared minute tick. */
final class TimeRenderBrick extends TextRenderBrick {

    private SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());

    TimeRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.TIME, host.prefs().time, R.id.timeText);
    }

    @Override
    boolean needsClockTick() {
        return true;
    }

    @Override
    void onClockTick(@NonNull Date now) {
        setTextIfChanged(format.format(now));
    }

    @Override
    void onConfigurationChanged() {
        // Rebuild against the new locale — the pattern is fixed but its digits are not.
        format = new SimpleDateFormat("HH:mm", Locale.getDefault());
    }
}
