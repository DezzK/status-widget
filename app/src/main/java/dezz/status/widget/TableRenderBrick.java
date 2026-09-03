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
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * A brick whose content is a TABLE of small readouts — an ordered list of cells, each an icon, a
 * value, or both, dealt into a grid. The counterpart of {@link TextRenderBrick} for content that
 * is a list rather than a sentence.
 *
 * <p>Everything a grid of readouts needs lives here: the cell views and their reuse, the row/column
 * arithmetic, the settings pass (font, icon size, outline, spacing, offset, opacity, margins), the
 * height floor, and the width discipline that keeps a value changing once a second from resizing
 * the row. A subclass supplies only what is genuinely its own — which cells exist right now, and
 * what each one says.
 *
 * <h3>Why one row is not a special case</h3>
 * {@link Preferences.TableBrickPrefs#rows} is the user's control, and one row IS the single-line
 * layout. Columns are then derived, so a brick never has to describe the same arrangement twice.
 * Cells fill row by row, in the subclass's order, because that order is what makes an unlabelled
 * readout readable.
 *
 * <h3>Width discipline</h3>
 * A value view never SHRINKS: it remembers the widest text it has been given since the last
 * settings pass and keeps that width. Feeds here tick about once a second, and in floating mode any
 * measured-size change fires {@link BufferingLinearLayout}'s size hint and the container's CHANGING
 * transition, which widen the window to the whole display for the length of an animation. A
 * hard-coded reserve for the widest value a field could theoretically reach would be stable but
 * mostly empty — a car showing "68 m" would carry the box for "-9999 m" all day. Growing on demand
 * gives a tight readout that settles after the first few seconds and then stops moving.
 */
abstract class TableRenderBrick extends RenderBrick {

    /**
     * A cell the brick wants drawn. Identity is the {@code key}: the view for a key is created
     * once and then reused, so a settings pass that leaves the cell list alone touches no views.
     */
    protected static final class CellSpec {
        final String key;
        @DrawableRes
        final int iconRes;
        final boolean hasValue;

        /** A cell with an icon and a number beside it. */
        protected CellSpec(@NonNull String key, @DrawableRes int iconRes, boolean hasValue) {
            this.key = key;
            this.iconRes = iconRes;
            this.hasValue = hasValue;
        }
    }

    /** A live cell's views, handed back to the subclass so it can fill them. */
    protected static final class Cell {
        final CellSpec spec;
        final View root;
        final OutlineImageView icon;
        @Nullable
        final OutlineTextView value;
        /** Widest text this cell has shown since the last settings pass — see the class doc. */
        int reservedWidth;

        Cell(CellSpec spec, View root, OutlineImageView icon, @Nullable OutlineTextView value) {
            this.spec = spec;
            this.root = root;
            this.icon = icon;
            this.value = value;
        }
    }

    /** Icon art side as a fraction of the font size, before the halo margin. */
    private static final float ICON_ART_RATIO = 0.86f;
    /** Halo breathing room, as a fraction of the font size, when the outline asks for less. */
    private static final float ICON_PAD_RATIO = 0.05f;
    /** Gap between an icon and the value it labels. */
    private static final float ICON_GAP_RATIO = 0.15f;

    private final List<Cell> cells = new ArrayList<>();

    private GridLayout grid;
    /** Height of one cell row, recomputed with the settings and read by the height floor. */
    private int rowHeight;

    protected TableRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type) {
        super(host, type);
    }

    // ── what a subclass supplies ─────────────────────────────────────────────

    /** This brick's settings. Same object as {@code prefs().brickPrefs(type)}, statically typed. */
    @NonNull
    protected abstract Preferences.TableBrickPrefs tablePrefs();

    /** The grid in a freshly inflated binding. One line per subclass, so no switch here. */
    @IdRes
    protected abstract int gridId();

    /** The cells to show right now, in reading order. An empty list hides the brick. */
    @NonNull
    protected abstract List<CellSpec> cellSpecs();

    /**
     * Fill one cell from live data: its text, its tint, and whether it is lit at all. Called for
     * every cell on every refresh — use {@link #setValue} and {@link #setTint}, which skip
     * unchanged writes, because this runs about once a second.
     */
    protected abstract void fillCell(@NonNull Cell cell);

    // ── binding ──────────────────────────────────────────────────────────────

    @Override
    void bind(@NonNull OverlayStatusWidgetBinding binding) {
        grid = binding.getRoot().findViewById(gridId());
        // The views belonged to the binding that was just thrown away; the DATA behind them lives
        // in the brick's source and survives, which is the split RenderBrick's class doc asks for.
        grid.removeAllViews();
        cells.clear();
    }

    @NonNull
    @Override
    View view() {
        return grid;
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    @Override
    int minHeight() {
        if (cells.isEmpty()) return 0;
        // cells.size(), never a live "visible" count: the floor is a pure function of the user's
        // switches and must not move when a lamp lights. And the FILLED row count, not the
        // requested one, or the row would be pinned taller than anything the grid draws.
        int rows = gridRows(gridColumns());
        return rowHeight * rows + tablePrefs().cellGap.get() * (rows - 1);
    }

    private int clampRows() {
        return Math.max(1, Math.min(4, tablePrefs().rows.get()));
    }

    /**
     * Columns the cells are dealt into. Rows are the user's control and columns follow — and then
     * the rows the grid actually FILLS follow from the columns, which is not always the number
     * that was asked for: four cells over three rows is a two-column table two rows tall. Every
     * caller wants the filled count, so nothing may use the requested one directly.
     *
     * <p>Callers must have checked that {@code cells} is non-empty.
     */
    private int gridColumns() {
        int requested = Math.min(clampRows(), cells.size());
        return (cells.size() + requested - 1) / requested;
    }

    private int gridRows(int columns) {
        return (cells.size() + columns - 1) / columns;
    }

    // ── settings ─────────────────────────────────────────────────────────────

    @Override
    void applySettings() {
        if (grid == null) return;
        Preferences.TableBrickPrefs prefs = tablePrefs();
        List<CellSpec> specs = cellSpecs();
        syncCells(specs);
        if (cells.isEmpty()) return;

        int fontSize = prefs.fontSize.get();
        int outlineWidth = prefs.outlineWidth.get();
        int outlineAlpha = prefs.outlineAlpha.get();
        int outline = outlineColor(host.themed(), outlineAlpha);
        Typeface typeface = Fonts.resolve(host.context(), prefs.fontFamily.get(),
                prefs.fontBold.get(), prefs.fontItalic.get());

        // OutlineImageView DILATES by its radius; OutlineTextView strokes the glyph contour with a
        // CENTRED stroke, which bleeds only half of it outward. Half the width is what puts an
        // icon's halo at the same visual weight as the digits it labels — in a table the two sit
        // inside one token, a fraction of an em apart, where a mismatch is unmissable.
        int haloRadius = (outlineAlpha > 0 && outlineWidth > 0)
                ? Math.max(1, Math.round(outlineWidth / 2f)) : 0;

        // Pass 1 — the paint, and the row height it implies.
        rowHeight = fontSize;
        for (Cell cell : cells) {
            if (cell.value == null) continue;
            cell.value.setTypeface(typeface);
            cell.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
            cell.value.setOutlineColor(outline);
            cell.value.setOutlineWidth(outlineWidth);
            // The remembered width was measured in the OLD paint, so it means nothing now.
            cell.reservedWidth = 0;
            cell.value.setWidth(0);
            rowHeight = Math.max(rowHeight, TextRenderBrick.lineHeight(cell.value, fontSize));
        }

        // Pass 2 — the icon boxes, which need the row height pass 1 just produced.
        // The padding is what makes the halo possible at all: OutlineImageView renders the
        // drawable into a bitmap the size of the VIEW and dilates INSIDE it, so art that fills the
        // view has its halo sliced off flat at the bounds — and an overlay icon with no halo
        // vanishes against a matching background, which is the whole reason this view type is
        // used. Derived from the font size and the outline width ONLY, because those two are
        // exactly what OutlineImageView's cache key contains; padding that could move while the
        // key stood still would serve a stale halo bitmap.
        int iconPad = Math.max(haloRadius, Math.round(fontSize * ICON_PAD_RATIO));
        int iconBox = Math.min(Math.round(fontSize * ICON_ART_RATIO) + 2 * iconPad, rowHeight);
        int iconGap = Math.round(fontSize * ICON_GAP_RATIO);
        for (Cell cell : cells) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.icon.getLayoutParams();
            lp.width = iconBox;
            lp.height = iconBox;
            lp.setMarginEnd(cell.value != null ? iconGap : 0);
            cell.icon.setLayoutParams(lp);
            cell.icon.setPadding(iconPad, iconPad, iconPad, iconPad);
            cell.icon.setOutlineColor(outline);
            cell.icon.setOutlineWidth(haloRadius);   // 0 when the user wants no outline
        }

        placeCells();
        applyHorizontalMargins(grid, prefs.marginStart.get(), prefs.marginEnd.get());
        grid.setTranslationY(prefs.adjustY.get());
        grid.setAlpha(prefs.contentAlpha.get() / 255f);
    }

    /**
     * Deal EVERY cell into the grid, a dark lamp included: an unlit cell holds its slot as
     * INVISIBLE rather than dropping out, so the arrangement is a pure function of the user's
     * switches and cannot move at the rate the lamps flip at — see {@link #setLit} for what a
     * collapsing cell would cost. The table can therefore look sparse where the lamps are, and
     * that is the accepted trade.
     *
     * <p>Rows are the user's control and columns follow: the scarce dimension on a head unit is
     * height, so the brick is told how tall it may be and works out how wide it has to get.
     */
    private void placeCells() {
        grid.removeAllViews();
        if (cells.isEmpty()) return;

        int columns = gridColumns();
        int rows = gridRows(columns);
        // Detach first, THEN size the grid, THEN re-attach with the new specs. GridLayout
        // validates a child's indices against the counts and the counts against the children's
        // indices, so any order that changes one while the other still describes the old table
        // throws — shrinking it took the service down one way, growing it the other. With no
        // children attached there is nothing to disagree with.
        grid.setRowCount(rows);
        grid.setColumnCount(columns);

        int gap = tablePrefs().cellGap.get();
        for (int i = 0; i < cells.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            // CENTER on the row spec, explicitly: an undefined row alignment resolves to
            // BASELINE, and an icon-only cell has no baseline, so the lamps hung a few pixels
            // above the numbers beside them. This matches the CENTER_VERTICAL the cell root
            // already applies to its own icon and value.
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(row, GridLayout.CENTER),
                    GridLayout.spec(column, GridLayout.START));
            // The gap belongs BETWEEN cells, so the last column and last row do not carry it —
            // otherwise the brick would sit a gap away from whatever follows it in the widget.
            lp.setMarginEnd(column < columns - 1 ? gap : 0);
            lp.bottomMargin = row < rows - 1 ? gap : 0;
            grid.addView(cells.get(i).root, lp);
        }
    }

    /**
     * Bring the child views in line with the cell list. Rebuilds only when the KEYS changed — this
     * runs on every settings pass, and a brick that recreated its cells each time would throw away
     * the measured layout the row around it depends on.
     */
    private void syncCells(@NonNull List<CellSpec> specs) {
        boolean same = cells.size() == specs.size();
        if (same) {
            for (int i = 0; i < specs.size(); i++) {
                if (!cells.get(i).spec.key.equals(specs.get(i).key)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        grid.removeAllViews();
        cells.clear();
        Context ctx = host.context();
        for (CellSpec spec : specs) {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setLayoutParams(new GridLayout.LayoutParams());

            OutlineImageView icon = new OutlineImageView(ctx);
            icon.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
            icon.setImageResource(spec.iconRes);
            root.addView(icon);

            OutlineTextView value = null;
            if (spec.hasValue) {
                value = new OutlineTextView(ctx);
                value.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                value.setIncludeFontPadding(false);
                value.setMaxLines(1);
                value.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                root.addView(value);
            }

            grid.addView(root);
            cells.add(new Cell(spec, root, icon, value));
        }
    }

    // ── content ──────────────────────────────────────────────────────────────

    @Override
    void refreshContent() {
        if (grid == null) return;
        for (Cell cell : cells) {
            fillCell(cell);
        }
    }

    /**
     * Write a cell's value, growing its reserved width if this text is the widest yet. Skips
     * unchanged text: {@code setText} drops the layout and relayouts even for equal strings.
     */
    protected static void setValue(@NonNull Cell cell, @NonNull String text) {
        if (cell.value == null) return;
        if (!text.contentEquals(cell.value.getText())) {
            cell.value.setText(text);
        }
        int width = (int) Math.ceil(cell.value.getPaint().measureText(text));
        if (width > cell.reservedWidth) {
            cell.reservedWidth = width;
            // setWidth, not setMinWidth: TextView's no-relayout fast path in checkForRelayout
            // needs mMinWidth == mMaxWidth, and that fast path is the entire point of pinning.
            cell.value.setWidth(width);
        }
    }

    /** Paint a cell's icon and value in one colour, skipping the write when it already is. */
    protected static void setTint(@NonNull Cell cell, int color) {
        android.content.res.ColorStateList current = ImageViewCompat.getImageTintList(cell.icon);
        if (current == null || current.getDefaultColor() != color) {
            ImageViewCompat.setImageTintList(cell.icon,
                    android.content.res.ColorStateList.valueOf(color));
        }
        if (cell.value != null && cell.value.getCurrentTextColor() != color) {
            cell.value.setTextColor(color);
        }
    }

    /**
     * Light or darken a cell. INVISIBLE, never GONE, and that is load-bearing: a lamp is NOT a
     * rare event. The road verdict is recomputed from a ~1 Hz feed and flips between consecutive
     * samples at a road edge, in a car park or at an intersection, and dead reckoning flaps at
     * every tunnel mouth. Each GONE↔VISIBLE flip would change the grid's measured width, relayout
     * the widget container, and trip its CHANGING transition — which pre-expands the overlay
     * WINDOW to the full display for the animation. That window is touchable, so the widget would
     * swallow taps across the whole screen about once a second while the car sits at a junction.
     * GridLayout measures INVISIBLE exactly like VISIBLE, so holding the slot costs nothing.
     */
    protected static void setLit(@NonNull Cell cell, boolean lit) {
        int visibility = lit ? View.VISIBLE : View.INVISIBLE;
        if (cell.root.getVisibility() != visibility) {
            cell.root.setVisibility(visibility);
        }
    }

    /** The widget's primary ink, for a cell with nothing special to say. */
    protected final int primaryColor() {
        return ContextCompat.getColor(host.themed(), R.color.text_primary);
    }
}
