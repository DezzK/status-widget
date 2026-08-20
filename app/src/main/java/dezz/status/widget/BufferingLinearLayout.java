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
    private boolean measureUnconstrainedWidth;
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

    /**
     * Floating mode only: measure children at their natural width, ignoring the width the parent
     * offers. Must stay OFF for the full-width status-bar row, whose spacers carry
     * {@code layout_weight} and need a bounded width to distribute.
     */
    public void setMeasureUnconstrainedWidth(boolean unconstrained) {
        if (measureUnconstrainedWidth == unconstrained) return;
        measureUnconstrainedWidth = unconstrained;
        // The remembered size belongs to the old regime; comparing against it would report a
        // phantom resize on the first measure after the switch.
        lastMeasuredWidth = -1;
        lastMeasuredHeight = -1;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int naturalWidth;
        if (measureUnconstrainedWidth
                && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // A floating overlay must never be squeezed to fit a width it was never asked to fit.
            // ViewRootImpl pre-measures a WRAP_CONTENT window at config_prefDialogWidth (580px on
            // a 720dp head unit); the text bricks silently wrap to that, so the widget renders
            // permanently narrower and taller than its content — and, far worse, it renders
            // DIFFERENTLY from the buffered state, because beginBufferedTransition swaps the
            // window to an EXACTLY(screenWidth) spec which lifts the cap. The two states then
            // disagree about the widget's height, every brick re-centres, LayoutTransition
            // CHANGING arms, and the transition re-opens the buffer that produced the other
            // state — a self-sustaining relayout loop that reads as a flicker on the window's
            // right edge (the only edge that can move; params.x is pinned, gravity is TOP|LEFT).
            //
            // Measuring the children unconstrained makes the layout identical under both specs,
            // and resolveSizeAndState reports MEASURED_STATE_TOO_SMALL so ViewRootImpl retries
            // with the room the content actually needs. Driven by an explicit flag rather than by
            // the incoming spec, because the status-bar row also arrives as AT_MOST (a
            // wrap_content container inside a match_parent window) and there the offered width is
            // real: its spacers carry layout_weight and need it to distribute.
            // Measure against the DISPLAY, not against whatever the window happens to offer.
            // Not UNSPECIFIED: content genuinely wider than the screen must still wrap the way it
            // always did, rather than being laid out off-screen and clipped away for good.
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(
                            getResources().getDisplayMetrics().widthPixels, MeasureSpec.AT_MOST),
                    heightMeasureSpec);
            naturalWidth = getMeasuredWidth();
            setMeasuredDimension(
                    resolveSizeAndState(naturalWidth, widthMeasureSpec, 0),
                    getMeasuredHeightAndState());
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            naturalWidth = getMeasuredWidth();
        }
        int newH = getMeasuredHeight();
        // Compare the NATURAL width, never the value resolveSizeAndState just clamped to the
        // offered spec: ViewRootImpl probes a WRAP_CONTENT window at config_prefDialogWidth
        // first, and reporting that probe as a shrink would open the window buffer on a size the
        // content never actually had.
        if (hint != null && lastMeasuredWidth >= 0
                && (naturalWidth != lastMeasuredWidth || newH != lastMeasuredHeight)) {
            hint.onSizeAboutToChange(lastMeasuredWidth, naturalWidth, lastMeasuredHeight, newH);
        }
        lastMeasuredWidth = naturalWidth;
        lastMeasuredHeight = newH;
    }
}
