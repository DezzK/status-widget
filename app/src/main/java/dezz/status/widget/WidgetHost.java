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
import android.os.Handler;

import androidx.annotation.NonNull;

/**
 * Everything a {@link RenderBrick} needs that is not one of its own views: the service context,
 * the theme-resolved context, the preferences and the shared main-thread handler.
 *
 * <p>Mirrors {@link BrickBinderContext} on the settings side, and exists for the same reason — a
 * brick renders itself and feeds itself, and has no business reaching the window buffer, the
 * {@code LayoutTransition} or {@code params}. Those belong to the row, and the row is the
 * service's job.
 */
interface WidgetHost {

    /** The service context: {@code getSystemService}, receivers, {@code getString}, drawables. */
    @NonNull
    Context context();

    /**
     * The context theme-dependent COLOURS must be read from — never drawables, which resolve
     * their variant from the service context so a forced widget theme does not swap them.
     *
     * <p>Never cache it: {@code onConfigurationChanged} invalidates the themed context and the
     * next settings pass rebuilds it. Falls back to the service context in the window between
     * those two points, so a status broadcast landing there cannot crash.
     */
    @NonNull
    Context themed();

    @NonNull
    Preferences prefs();

    /**
     * The one subscription to location, satellite status and the gnss-share broadcast. Owned by
     * the service — a brick declares what it needs through {@link GnssProvider#setNeeds} in its
     * own {@code syncSource} and never registers a listener of its own.
     */
    @NonNull
    GnssProvider gnss();

    /**
     * The one main-thread handler. A brick may only remove ITS OWN runnables from it —
     * {@code removeCallbacksAndMessages(null)} would take the window-buffer safety close with it.
     */
    @NonNull
    Handler handler();

    /** The user's brick order, for callbacks that arrive without a settings pass in flight. */
    @NonNull
    java.util.Set<BrickType> currentOrder();

    /**
     * The row's per-app hide verdict for a brick. Asked rather than computed, because the answer
     * follows the {@code hideSource} inheritance and is snapshotted once per settings pass.
     */
    boolean isBrickHiddenByApp(@NonNull BrickType type);
}
