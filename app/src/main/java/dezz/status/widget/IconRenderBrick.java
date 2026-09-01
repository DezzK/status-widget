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
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * A brick that draws one status icon. Everything the three of them share lives here: the square
 * layout params, the design/style/outline pass, and the two badge channels. A subclass supplies
 * only what genuinely differs — its drawables, its state colours, which state it is in, and what
 * (if anything) it wants badged.
 *
 * <p>This replaces the {@code ICON_TYPE_WIFI/GNSS/BT} constants that used to index three shared
 * drawable tables and three colour tables by hand: a second per-type enumeration running parallel
 * to {@link BrickType}, kept in step by nothing but care.
 */
abstract class IconRenderBrick extends RenderBrick {

    /** Icon style indices — must match the {@code icon_styles} array in strings.xml. */
    private static final int STYLE_COLOR = 1;

    private final Preferences.IconBrickPrefs prefs;
    private OutlineImageView icon;

    protected IconRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                              @NonNull Preferences.IconBrickPrefs prefs) {
        super(host, type);
        this.prefs = prefs;
    }

    /** The icon view inside a freshly inflated binding. One line per subclass, so no switch. */
    @NonNull
    protected abstract OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding);

    /**
     * This brick's drawables: one row per icon design (classic / solid / bars, in the order of the
     * {@code icon_designs} array), each row indexed by the brick's own state ordinal.
     */
    @NonNull
    protected abstract int[][] designs();

    /** Colour resource per state ordinal, used when the icon style is "coloured". */
    @NonNull
    protected abstract int[] stateColors();

    /** The brick's current state, as an ordinal into {@link #designs()} and {@link #stateColors()}. */
    protected abstract int stateOrdinal();

    /**
     * Overlay badge drawn on top of the icon, or {@code null}. Resolved from the SERVICE context:
     * a badge drawable is artwork, not a colour, and must not follow a forced widget theme.
     */
    @Nullable
    protected Drawable badgeDrawable(int stateIdx) {
        return null;
    }

    /**
     * Text badge, or {@code null} for none. {@code styleBg} is the background the icon's own
     * colouring would give it, which most badges just adopt.
     */
    @Nullable
    protected TextBadge textBadge(int stateIdx, int styleBg) {
        return null;
    }

    /** A text badge: what to write, the two colours to write it in, and an optional rim colour. */
    static final class TextBadge {
        final String text;
        final int background;
        final int foreground;
        /**
         * Colour of the ring around the pill, or {@code 0} for none. A SECOND channel on one badge:
         * the fill already carries the brick's primary state, so a badge that has to say two things
         * at once says the other one on its rim rather than fighting for the fill.
         */
        final int ring;

        TextBadge(@NonNull String text, int background, int foreground) {
            this(text, background, foreground, 0);
        }

        TextBadge(@NonNull String text, int background, int foreground, int ring) {
            this.text = text;
            this.background = background;
            this.foreground = foreground;
            this.ring = ring;
        }
    }

    @Override
    void bind(@NonNull OverlayStatusWidgetBinding binding) {
        icon = iconOf(binding);
    }

    @NonNull
    @Override
    View view() {
        return icon;
    }

    @Override
    void applySettings() {
        if (icon == null) return;
        int size = prefs.size.get();
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = size;
        lp.height = size;
        icon.setLayoutParams(lp);
        applyHorizontalMargins(icon, prefs.marginStart.get(), prefs.marginEnd.get());
        icon.setTranslationY(prefs.adjustY.get());
        icon.setAlpha(prefs.contentAlpha.get() / 255f);
    }

    @Override
    int minHeight() {
        return prefs.size.get();
    }

    /**
     * Repaint the icon for its current state: drawable for the chosen design, tint for the chosen
     * style, outline halo, and both badge channels.
     */
    @Override
    void refreshContent() {
        if (icon == null) return;
        int[][] designs = designs();
        int designIdx = clamp(prefs().iconDesign.get(), 0, designs.length - 1);
        int[] design = designs[designIdx];
        int stateIdx = clamp(stateOrdinal(), 0, design.length - 1);
        icon.setImageResource(design[stateIdx]);
        icon.setDrawIcon(true);

        Context ctx = host.themed();
        boolean coloured = clamp(prefs().iconStyle.get(), 0, 1) == STYLE_COLOR;
        int stateColor = ContextCompat.getColor(ctx, stateColors()[stateIdx]);
        int tint = coloured ? stateColor : ContextCompat.getColor(ctx, R.color.text_primary);
        // Skip the no-op tint set: applyImageTint invalidates the drawable unconditionally, and
        // this runs on every periodic status broadcast.
        ColorStateList currentTint = ImageViewCompat.getImageTintList(icon);
        if (currentTint == null || currentTint.getDefaultColor() != tint) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));
        }

        int outlineAlpha = prefs.outlineAlpha.get();
        if (outlineAlpha > 0) {
            icon.setOutlineColor((ContextCompat.getColor(ctx, R.color.text_outline) & 0x00FFFFFF)
                    | (outlineAlpha << 24));
            icon.setOutlineWidth(prefs.outlineWidth.get());
        } else {
            icon.setOutlineWidth(0);
        }

        // Both badge channels are written on every repaint, each with its own clear value — they
        // are independent, and leaving one alone would strand a badge from a previous state.
        icon.setBadgeDrawable(badgeDrawable(stateIdx));
        TextBadge badge = textBadge(stateIdx, coloured ? stateColor
                : ContextCompat.getColor(ctx, R.color.text_primary));
        if (badge != null) {
            icon.setBadgeText(badge.text, badge.background, badge.foreground, badge.ring);
        } else {
            icon.setBadgeText(null, 0, 0, 0);
        }
    }

    /** Default badge ink: the widget's outline colour at full opacity, so it flips with the theme. */
    protected final int defaultBadgeForeground() {
        return ContextCompat.getColor(host.themed(), R.color.text_outline) | 0xFF000000;
    }

    protected static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** True once {@link #bind} has run — callbacks can land before the overlay exists. */
    protected final boolean hasView() {
        return icon != null;
    }
}
