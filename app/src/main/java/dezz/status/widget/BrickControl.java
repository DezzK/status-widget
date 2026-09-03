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

/**
 * The optional parts of the SHARED brick card — {@code brick_item.xml} only. One container per
 * constant, so {@link Preferences.BrickPrefs#controls()} fully describes which parts of the card
 * a brick shows, with no per-type switch in {@link BrickListAdapter}.
 *
 * <p>A brick's OWN settings block is not described here: it lives in its own layout file and is
 * registered in {@link BrickBlock}, inflated once into {@code brickTypeBlockSlot} by
 * {@code onCreateViewHolder}. Adding a brick-specific block therefore adds nothing to this enum.
 *
 * <p>Adding a SHARED control means: one constant here, one container in {@code brick_item.xml},
 * one visibility line plus one binding branch in {@code BrickViewHolder}, and the brick classes
 * that want it add it in their {@code controls()} override.
 */
public enum BrickControl {
    /**
     * Left column of {@code brickSizeAdjustRow} — font size / icon size. Split from
     * {@link #COL_ADJUST_Y} because the media brick wants the vertical offset but carries three
     * font-size sliders of its own, one per section.
     */
    COL_SIZE,
    /** Right column of {@code brickSizeAdjustRow} — the vertical offset. See {@link #COL_SIZE}. */
    COL_ADJUST_Y,
    /** {@code brickOutlineRow} — outline opacity + outline width. */
    ROW_OUTLINE,
    /** {@code brickContentAlphaRow} — whole-element opacity. */
    ROW_CONTENT_ALPHA,
    /** {@code brickMarginRow} — start / end margins. */
    ROW_MARGIN,
    /**
     * {@code brickFontBlock} — font family + bold/italic. Text bricks only, and not media, whose
     * three sections each pick their own font. The card has a single per-type slot, so this block
     * cannot move into a per-type layout without a second one; it stays a shared row toggled by a
     * flag, and the binding site casts to {@link Preferences.TextBrickPrefs} — safe only while
     * this constant is added in exactly one place.
     */
    BLOCK_FONT,
    /**
     * {@code brickTableRow} — row count + cell spacing, for a brick whose content is a table of
     * cells rather than one run of text. Added by {@link Preferences.TableBrickPrefs} and NOWHERE
     * else: the binding site casts to that class, and the cast is only safe while this stays the
     * single origin — the same rule {@link #BLOCK_FONT} lives under.
     */
    ROW_TABLE,
    /**
     * {@code brickStatusAlignmentLayout} — the shared start/center/end dropdown for status-bar
     * mode. Bricks whose own block hosts a paired alignment row drop this and bind their in-block
     * dropdown instead. Its container is driven by the binder rather than by the control set
     * alone, because visibility also depends on {@code prefs.widgetMode}.
     */
    SHARED_STATUS_ALIGNMENT,
}
