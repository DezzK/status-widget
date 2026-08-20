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

import androidx.annotation.IdRes;

/**
 * Binds one brick's own settings block — the part of a brick card that no other brick shares.
 *
 * <p>A binder owns exactly the views of ONE block layout and the prefs object behind them, and
 * captures both at construction: a holder serves exactly one {@link BrickType} for its whole life
 * (see {@link BrickListAdapter#getItemViewType}), and {@link Preferences#brickPrefs} hands out
 * per-type singletons. That is what removes the casts — a subclass declares the concrete prefs
 * type it wants and never tests for it.
 *
 * <p>Registered in {@link BrickBlock}, which is the only place that knows a block's layout and
 * its binder together.
 */
abstract class BrickBlockBinder {
    protected final BrickBinderContext ctx;
    private final View root;

    protected BrickBlockBinder(BrickBinderContext ctx, View root) {
        this.ctx = ctx;
        this.root = root;
    }

    /**
     * The only entry point, and deliberately {@code final}: clearing listeners first is not
     * optional. {@code Slider.addOnChangeListener}, {@code addOnButtonCheckedListener} and
     * {@link ViewBinder#linkPairDisableOnZero} all APPEND rather than replace, and every visible
     * card is rebound on every {@code notifyDataSetChanged()} — most often when the user expands
     * a different brick. Without the reset, one listener per expand accumulates on every slider.
     */
    final void rebind() {
        clearListeners();
        bindViews();
    }

    /**
     * Detach the listeners whose setters APPEND, and null out any {@code setOnCheckedChangeListener}
     * whose view is re-seeded with {@code setChecked} in {@link #bindViews} — seeding fires a
     * stale listener otherwise. Setters with replace semantics ({@code setOnClickListener},
     * {@code setOnItemClickListener}) need nothing here.
     */
    protected abstract void clearListeners();

    /** Seed every control from the prefs and attach fresh listeners. */
    protected abstract void bindViews();

    /** Scoped lookup: a binder can only ever reach views inside its own block. */
    protected final <T extends View> T find(@IdRes int id) {
        return root.findViewById(id);
    }
}
