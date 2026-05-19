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
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * LinearLayout that fires a {@link SizeChangeHint} immediately at the end of {@code onMeasure}
 * whenever its measured width or height differs from the previous pass.
 * <p>
 * Used by the overlay container so that {@link WidgetService} can re-size the WindowManager
 * window <i>during</i> the measure pass — before {@code ViewRootImpl} reaches
 * {@code relayoutWindow} with the new wrap_content size and snaps the floating window down.
 * The standard
 * {@link android.view.View#addOnLayoutChangeListener(android.view.View.OnLayoutChangeListener)}
 * hook fires too late (post-layout, post-relayoutWindow) and a shrinking widget would clip
 * its still-animating children on the right edge for one frame.
 */
public class BufferingLinearLayout extends LinearLayout {
    public interface SizeChangeHint {
        /** Called from {@code onMeasure} when measured dimensions differ from the previous pass.
         *  Invoked synchronously inside the layout pass; do not call {@code requestLayout()} from
         *  here. Calling {@code windowManager.updateViewLayout(...)} is fine — it schedules a
         *  fresh traversal asynchronously and does not re-enter this {@code onMeasure}. */
        void onSizeAboutToChange(int oldWidth, int newWidth, int oldHeight, int newHeight);
    }

    @Nullable private SizeChangeHint hint;
    private int lastMeasuredWidth = -1;
    private int lastMeasuredHeight = -1;

    public BufferingLinearLayout(@NonNull Context context) {
        super(context);
    }

    public BufferingLinearLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BufferingLinearLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSizeChangeHint(@Nullable SizeChangeHint hint) {
        this.hint = hint;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int newW = getMeasuredWidth();
        int newH = getMeasuredHeight();
        if (hint != null && lastMeasuredWidth >= 0
                && (newW != lastMeasuredWidth || newH != lastMeasuredHeight)) {
            hint.onSizeAboutToChange(lastMeasuredWidth, newW, lastMeasuredHeight, newH);
        }
        lastMeasuredWidth = newW;
        lastMeasuredHeight = newH;
    }
}
