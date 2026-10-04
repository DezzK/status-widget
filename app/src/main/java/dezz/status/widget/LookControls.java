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
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.slider.Slider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Binds one element's look — a {@link Preferences.LookPrefs} — to one include of
 * {@code brick_look_controls.xml}: size and vertical offset, outline, opacity, margins. A brick
 * card holds two, the brick's own and a reading's icon, and both are bound here.
 *
 * <p>Every view is looked up inside the include's own root, never through the card: the include's
 * ids repeat within one card.
 */
final class LookControls {

    /** The rows this binder can show — the {@link BrickControl}s that describe a look. */
    static final EnumSet<BrickControl> ALL = EnumSet.of(
            BrickControl.COL_SIZE,
            BrickControl.COL_ADJUST_Y,
            BrickControl.ROW_OUTLINE,
            BrickControl.ROW_CONTENT_ALPHA,
            BrickControl.ROW_MARGIN);

    private final BrickBinderContext ctx;
    private final View root;
    private final View sizeAdjustRow;
    private final View sizeColumn;
    private final View adjustYColumn;
    private final View outlineRow;
    private final View contentAlphaRow;
    private final View marginRow;
    private final TextView sizeLabel;
    private final Slider sizeSlider;
    private final Slider adjustYSlider;
    private final Slider outlineAlphaSlider;
    private final Slider outlineWidthSlider;
    private final Slider contentAlphaSlider;
    private final Slider marginStartSlider;
    private final Slider marginEndSlider;
    /** Rows shown; see {@link #showRows}. */
    private EnumSet<BrickControl> rows = EnumSet.copyOf(ALL);

    LookControls(@NonNull BrickBinderContext ctx, @NonNull View root) {
        this.ctx = ctx;
        this.root = root;
        sizeAdjustRow = root.findViewById(R.id.lookSizeAdjustRow);
        sizeColumn = root.findViewById(R.id.lookSizeColumn);
        adjustYColumn = root.findViewById(R.id.lookAdjustYColumn);
        outlineRow = root.findViewById(R.id.lookOutlineRow);
        contentAlphaRow = root.findViewById(R.id.lookContentAlphaRow);
        marginRow = root.findViewById(R.id.lookMarginRow);
        sizeLabel = root.findViewById(R.id.lookSizeLabel);
        sizeSlider = root.findViewById(R.id.lookSizeSlider);
        adjustYSlider = root.findViewById(R.id.lookAdjustYSlider);
        outlineAlphaSlider = root.findViewById(R.id.lookOutlineAlphaSlider);
        outlineWidthSlider = root.findViewById(R.id.lookOutlineWidthSlider);
        contentAlphaSlider = root.findViewById(R.id.lookContentAlphaSlider);
        marginStartSlider = root.findViewById(R.id.lookMarginStartSlider);
        marginEndSlider = root.findViewById(R.id.lookMarginEndSlider);
    }

    /**
     * Which rows of the look to show, out of {@link #ALL}; anything else in {@code controls} is
     * not this binder's business. A brick's control set is fixed for a holder's life, so this is
     * called once rather than on every rebind.
     */
    void showRows(@NonNull Set<BrickControl> controls) {
        rows = EnumSet.noneOf(BrickControl.class);
        for (BrickControl control : controls) {
            if (ALL.contains(control)) rows.add(control);
        }
        sizeAdjustRow.setVisibility(rows.contains(BrickControl.COL_SIZE)
                || rows.contains(BrickControl.COL_ADJUST_Y) ? View.VISIBLE : View.GONE);
        sizeColumn.setVisibility(visibilityOf(BrickControl.COL_SIZE));
        adjustYColumn.setVisibility(visibilityOf(BrickControl.COL_ADJUST_Y));
        outlineRow.setVisibility(visibilityOf(BrickControl.ROW_OUTLINE));
        contentAlphaRow.setVisibility(visibilityOf(BrickControl.ROW_CONTENT_ALPHA));
        marginRow.setVisibility(visibilityOf(BrickControl.ROW_MARGIN));
    }

    /** Show or hide the whole look, e.g. while the element it describes is switched off. */
    void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** See {@link BrickBlockBinder#clearListeners()}: slider listeners APPEND. */
    void clearListeners() {
        sizeSlider.clearOnChangeListeners();
        adjustYSlider.clearOnChangeListeners();
        outlineAlphaSlider.clearOnChangeListeners();
        outlineWidthSlider.clearOnChangeListeners();
        contentAlphaSlider.clearOnChangeListeners();
        marginStartSlider.clearOnChangeListeners();
        marginEndSlider.clearOnChangeListeners();
    }

    void bind(@NonNull Preferences.LookPrefs look) {
        if (rows.contains(BrickControl.COL_SIZE)) {
            sizeLabel.setText(look.sizeLabelRes);
            sizeSlider.setContentDescription(ctx.activity.getString(look.sizeLabelRes));
            sizeSlider.setValueFrom(look.sizeMin);
            sizeSlider.setValueTo(look.sizeMax);
            ctx.bindIntSlider(sizeSlider, look.size, ctx.sizeFormatter());
        }
        if (rows.contains(BrickControl.COL_ADJUST_Y)) {
            ctx.bindIntSlider(adjustYSlider, look.adjustY, ctx.offsetFormatter());
        }
        if (rows.contains(BrickControl.ROW_OUTLINE)) {
            ctx.bindIntSlider(outlineAlphaSlider, look.outlineAlpha, ctx.plainFormatter());
            ctx.bindIntSlider(outlineWidthSlider, look.outlineWidth, ctx.sizeFormatter());
            // After both values are seeded — the linker reads them to decide enabled state.
            ViewBinder.linkPairDisableOnZero(outlineAlphaSlider, outlineWidthSlider);
        }
        if (rows.contains(BrickControl.ROW_CONTENT_ALPHA)) {
            ctx.bindIntSlider(contentAlphaSlider, look.contentAlpha, ctx.plainFormatter());
        }
        if (rows.contains(BrickControl.ROW_MARGIN)) {
            ctx.bindIntSlider(marginStartSlider, look.marginStart, ctx.sizeFormatter());
            ctx.bindIntSlider(marginEndSlider, look.marginEnd, ctx.sizeFormatter());
        }
    }

    private int visibilityOf(@NonNull BrickControl control) {
        return rows.contains(control) ? View.VISIBLE : View.GONE;
    }
}
