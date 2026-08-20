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
 * A block that is nothing but one switch — the shape GPS and Bluetooth share. Instantiated
 * directly from {@link BrickBlock} rather than subclassed, so neither brick needs a class of its
 * own.
 */
final class SingleSwitchBlockBinder extends BrickBlockBinder {
    private final CompoundButton toggle;
    private final Preferences.Bool pref;

    SingleSwitchBlockBinder(BrickBinderContext ctx, View root, @IdRes int switchId,
                            Preferences.Bool pref) {
        super(ctx, root);
        this.toggle = find(switchId);
        this.pref = pref;
    }

    @Override
    protected void clearListeners() {
        toggle.setOnCheckedChangeListener(null);
    }

    @Override
    protected void bindViews() {
        ctx.bindSwitch(toggle, pref, null);
    }
}
