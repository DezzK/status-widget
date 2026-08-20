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

import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * The render-side counterpart of {@link Preferences.BrickPrefs}: one object per {@link BrickType},
 * owning that brick's views, its settings pass, its visibility answer, its contribution to the
 * height floor, and whatever data source feeds it.
 *
 * <h3>Lifetime</h3>
 * Created once per service and kept across overlay rebuilds; {@link #bind} re-captures the views
 * on every {@code createOverlayView()}, i.e. on every configuration change. The object outlives
 * the binding on purpose — the status enums, the connected-device set and the satellite counters
 * all survive a recreate today, and nothing re-registers the data sources afterwards. Only
 * view-derived state may be reset in {@link #bind}.
 *
 * <h3>Phases</h3>
 * The hooks below are grouped by the phase of {@code applyPreferences} that drives them, and the
 * service runs EVERY brick through a phase before ANY brick enters the next one. There is
 * deliberately no single {@code apply()} that takes one brick end to end: settings unconditionally
 * write {@code setAlpha}, which the visibility phase has to overwrite for keep-space bricks;
 * {@link #minHeight} reads the live paint, which the settings phase must have pushed the typeface
 * onto; the floor reads the container's padding back; and the whole batch shares one buffered
 * transition.
 */
abstract class RenderBrick {

    protected final WidgetHost host;
    final BrickType type;

    protected RenderBrick(@NonNull WidgetHost host, @NonNull BrickType type) {
        this.host = host;
        this.type = type;
    }

    protected final Preferences prefs() {
        return host.prefs();
    }

    // ── binding lifecycle ────────────────────────────────────────────────────

    /**
     * Capture this brick's views from a freshly inflated binding, and reset state derived from
     * them. Runs before the overlay is attached, so nothing here may assume a measured layout.
     * Data-source state must NOT be reset — see the class doc.
     */
    abstract void bind(@NonNull OverlayStatusWidgetBinding binding);

    /**
     * The brick's root view — the child the reorder pass moves between the container and the
     * status-bar groups. Not necessarily a leaf: the media brick's root is a container.
     */
    @NonNull
    abstract View view();

    // ── phase: per-brick settings ────────────────────────────────────────────

    /**
     * Push size / outline / margins / offset / opacity from the preferences onto the views. Runs
     * for every registered brick on every pass, including bricks absent from the user's order —
     * they stay attached and must keep their styling for the moment they come back.
     */
    abstract void applySettings();

    /**
     * Repaint whatever depends on live data rather than on preferences. Runs once per settings
     * pass, after the window has been positioned, and is also what a brick's own callbacks call
     * when their data changes.
     */
    void refreshContent() {
    }

    // ── phase: visibility ────────────────────────────────────────────────────

    /**
     * Whether the brick renders at all right now, ignoring the per-app hide rule. Membership in
     * the user's order by default; bricks with a content or availability gate narrow it.
     *
     * <p>Deliberately NOT the same question as {@link #countsTowardFloor} — keeping those apart
     * is the entire point of the height floor.
     */
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type);
    }

    /** Target opacity when visible, 0..1. Overridable: the media brick's container is not it. */
    float contentAlpha() {
        return prefs().brickPrefs(type).contentAlpha.get() / 255f;
    }

    // ── phase: height floor ──────────────────────────────────────────────────

    /**
     * Whether this brick raises the row's minimum height. Membership in the user's order by
     * default — including bricks currently hidden by the per-app rule, which is what stops the
     * row from collapsing when one disappears.
     */
    boolean countsTowardFloor(@NonNull Set<BrickType> order) {
        return order.contains(type);
    }

    /** The height this brick occupies at its tallest, in px. Only read when it counts. */
    abstract int minHeight();

    // ── phase: data sources ──────────────────────────────────────────────────

    /**
     * Reconcile whatever feeds this brick with whether the user still has it in the row. Runs on
     * every settings pass; each brick owns its own idempotence, because the three shapes in this
     * app genuinely differ — some acquire lazily behind a manager field, some re-register every
     * pass by design.
     */
    void syncSource(boolean active) {
    }

    /** Release everything. The service is going away. */
    void onDestroy() {
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    /**
     * Set a brick's horizontal margins. Bricks are direct children of a horizontal LinearLayout
     * in both widget modes, so the cast holds for every one of them.
     */
    static void applyHorizontalMargins(@Nullable View view, int start, int end) {
        if (view == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        view.setLayoutParams(lp);
    }
}
