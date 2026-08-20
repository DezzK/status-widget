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

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

/** Binds {@code brick_block_date.xml} — the date/day-of-week sub-toggles and the alignment pair. */
final class DateBlockBinder extends BrickBlockBinder {
    private final Preferences.DateBrickPrefs p;

    private final MaterialSwitch showDate;
    private final MaterialSwitch showDayOfWeek;
    private final MaterialSwitch showFullName;
    private final MaterialSwitch dateBeforeDayOfWeek;
    private final MaterialSwitch oneLineLayout;
    private final TextInputLayout statusAlignmentLayout;
    private final MaterialAutoCompleteTextView statusAlignmentDropdown;
    private final MaterialAutoCompleteTextView alignmentDropdown;

    DateBlockBinder(BrickBinderContext ctx, View root, Preferences.DateBrickPrefs p) {
        super(ctx, root);
        this.p = p;
        showDate = find(R.id.brickDateShowDate);
        showDayOfWeek = find(R.id.brickDateShowDayOfWeek);
        showFullName = find(R.id.brickDateShowFullName);
        dateBeforeDayOfWeek = find(R.id.brickDateBeforeDayOfWeek);
        oneLineLayout = find(R.id.brickDateOneLineLayout);
        statusAlignmentLayout = find(R.id.brickDateStatusAlignmentLayout);
        statusAlignmentDropdown = find(R.id.brickDateStatusAlignmentDropdown);
        alignmentDropdown = find(R.id.brickDateAlignmentDropdown);
    }

    @Override
    protected void clearListeners() {
        showDate.setOnCheckedChangeListener(null);
        showDayOfWeek.setOnCheckedChangeListener(null);
        showFullName.setOnCheckedChangeListener(null);
        dateBeforeDayOfWeek.setOnCheckedChangeListener(null);
        oneLineLayout.setOnCheckedChangeListener(null);
    }

    @Override
    protected void bindViews() {
        // "Date before day of week" and "one-line layout" both describe how the two sub-fields
        // relate to each other — meaningless if only one (or zero) of them is shown. Recompute
        // enabled state whenever either visibility switch changes.
        Runnable refreshPairControls = () -> {
            boolean bothShown = showDate.isChecked() && showDayOfWeek.isChecked();
            dateBeforeDayOfWeek.setEnabled(bothShown);
            oneLineLayout.setEnabled(bothShown);
        };

        ctx.bindSwitch(showDate, p.showDate, refreshPairControls);
        ctx.bindSwitch(showDayOfWeek, p.showDayOfWeek, refreshPairControls);
        ctx.bindSwitch(showFullName, p.showFullName, null);
        ctx.bindSwitch(dateBeforeDayOfWeek, p.dateBeforeDayOfWeek, null);
        ctx.bindSwitch(oneLineLayout, p.oneLineLayout, null);
        refreshPairControls.run();

        ctx.bindAlignmentDropdown(alignmentDropdown, p.alignment);
        // Paired with the text alignment in one weighted row, which is why the date brick drops
        // the shared status-alignment dropdown from its control set.
        ctx.bindStatusAlignmentDropdown(p.statusAlignment,
                statusAlignmentLayout, statusAlignmentDropdown);
    }
}
