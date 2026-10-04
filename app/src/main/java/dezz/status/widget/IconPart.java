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
import android.content.res.ColorStateList;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.math.MathUtils;
import androidx.core.widget.ImageViewCompat;

/**
 * An icon drawn the way the user set icons up: the drawable of the chosen icon design, tinted by
 * the chosen icon style, with an outline halo, sized and placed by a {@link Preferences.LookPrefs}.
 * The status icon bricks and the icon in front of a reading are both one of these, so what "an
 * icon" looks like is decided in one place.
 */
final class IconPart {

    /** Icon style index of "colored" — must match the {@code icon_styles} array in strings.xml. */
    private static final int STYLE_COLOR = 1;

    private final WidgetHost host;
    @Nullable
    private OutlineImageView view;

    IconPart(@NonNull WidgetHost host) {
        this.host = host;
    }

    /** Capture the view from a freshly inflated binding — see {@link RenderBrick#bind}. */
    void bind(@Nullable OutlineImageView view) {
        this.view = view;
    }

    @Nullable
    OutlineImageView view() {
        return view;
    }

    /** Size, margins, vertical offset and opacity, from the look. */
    void applyLook(@NonNull Preferences.LookPrefs look) {
        if (view == null) return;
        int size = look.size.get();
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        view.setLayoutParams(lp);
        RenderBrick.applyHorizontalMargins(view, look.marginStart.get(), look.marginEnd.get());
        view.setTranslationY(look.adjustY.get());
        view.setAlpha(look.contentAlpha.get() / 255f);
    }

    /**
     * The drawable for the chosen icon design. {@code designs} holds one row per design, in the
     * order of the {@code icon_designs} array, each row indexed by state.
     */
    @DrawableRes
    int drawableFor(@NonNull int[][] designs, int stateIdx) {
        int designIdx = MathUtils.clamp(host.prefs().iconDesign.get(), 0, designs.length - 1);
        int[] design = designs[designIdx];
        return design[MathUtils.clamp(stateIdx, 0, design.length - 1)];
    }

    /**
     * Repaint the icon: the drawable, the tint the chosen style gives it — its state color when
     * the style is colored, the text color otherwise — and the outline halo from the look.
     *
     * @return the color the icon is drawn in, which a badge riding on it adopts
     */
    int paint(@DrawableRes int drawable, @ColorRes int stateColor,
              @NonNull Preferences.LookPrefs look) {
        Context ctx = host.themed();
        boolean colored = MathUtils.clamp(host.prefs().iconStyle.get(), 0, 1) == STYLE_COLOR;
        int tint = ContextCompat.getColor(ctx, colored ? stateColor : R.color.text_primary);
        if (view == null) return tint;
        view.setImageResource(drawable);
        view.setDrawIcon(true);
        // Skip the no-op tint set: applyImageTint invalidates the drawable unconditionally, and
        // this runs on every status broadcast and every new reading.
        ColorStateList currentTint = ImageViewCompat.getImageTintList(view);
        if (currentTint == null || currentTint.getDefaultColor() != tint) {
            ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(tint));
        }
        int outlineAlpha = look.outlineAlpha.get();
        if (outlineAlpha > 0) {
            view.setOutlineColor(RenderBrick.outlineColor(ctx, outlineAlpha));
            view.setOutlineWidth(look.outlineWidth.get());
        } else {
            view.setOutlineWidth(0);
        }
        return tint;
    }
}
