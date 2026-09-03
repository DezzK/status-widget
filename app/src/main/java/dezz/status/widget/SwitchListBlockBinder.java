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
import android.widget.CompoundButton;

import androidx.annotation.IdRes;

/**
 * A block that is nothing but a list of INDEPENDENT switches — the shape Bluetooth, GPS and the
 * GNSS readout share. Instantiated directly from {@link BrickBlock} rather than subclassed, so
 * none of those bricks needs a class of its own.
 *
 * <p>Independent is the whole precondition: a block whose controls gate each other (the date's
 * "one line" only means something with both halves shown) needs a binder that can recompute
 * enabled state, and belongs in a class of its own.
 */
final class SwitchListBlockBinder extends BrickBlockBinder {

    /** One switch and the preference behind it. */
    static final class Row {
        @IdRes
        final int id;
        final Preferences.Bool pref;

        private Row(@IdRes int id, Preferences.Bool pref) {
            this.id = id;
            this.pref = pref;
        }
    }

    /** Pairs a switch id with its pref at the call site, so the two cannot drift apart. */
    static Row row(@IdRes int id, Preferences.Bool pref) {
        return new Row(id, pref);
    }

    private final CompoundButton[] toggles;
    private final Preferences.Bool[] prefs;

    SwitchListBlockBinder(BrickBinderContext ctx, View root, Row... rows) {
        super(ctx, root);
        toggles = new CompoundButton[rows.length];
        prefs = new Preferences.Bool[rows.length];
        for (int i = 0; i < rows.length; i++) {
            toggles[i] = find(rows[i].id);
            prefs[i] = rows[i].pref;
        }
    }

    @Override
    protected void clearListeners() {
        // Every switch, not just the ones that changed: bindViews re-seeds each with setChecked,
        // and every visible card is rebound whenever any other brick is expanded.
        for (CompoundButton toggle : toggles) {
            toggle.setOnCheckedChangeListener(null);
        }
    }

    @Override
    protected void bindViews() {
        for (int i = 0; i < toggles.length; i++) {
            ctx.bindSwitch(toggles[i], prefs[i], null);
        }
    }
}
