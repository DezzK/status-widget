/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A scroll view as tall as its content up to a height its owner sets, and scrolled past it — for text of any
 * length in a window sized to its content (the update card over other apps), which would otherwise grow off
 * the screen.
 */
public final class MaxHeightScrollView extends ScrollView {

    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMaxHeight(int px) {
        if (px == maxHeight) return;
        maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cap = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? maxHeight
                : Math.min(MeasureSpec.getSize(heightMeasureSpec), maxHeight);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(0, cap), MeasureSpec.AT_MOST));
    }
}
