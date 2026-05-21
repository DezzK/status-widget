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
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Single-line text view with seamless one-direction scrolling for overflowing strings.
 * <p>
 * Replaces Android's built-in {@code ellipsize="marquee"} which has two visual quirks the users
 * complained about on car head units:
 * <ul>
 *   <li>The text bounces back to the start with a brief pause every cycle — when the overflow
 *       is small, this reads as a "twitch" rather than smooth motion.</li>
 *   <li>It only animates while the view {@code isSelected()}, with subtly inconsistent
 *       timings across OEM ROMs.</li>
 * </ul>
 * Our approach: when the natural text width exceeds the available view width, render the
 * string twice with a separator in between and scroll {@code scrollX} continuously from
 * {@code 0} to {@code textWidth + separatorWidth}, wrapping back to {@code 0} on each loop.
 * Because the second copy is already on screen by the time the first scrolls off the left,
 * the wrap point is invisible — motion is uniform and one-directional. Short strings that
 * fit are shown statically; no animation kicks in.
 */
public class MarqueeOutlineTextView extends OutlineTextView {
    /** Separator between repetitions of the scrolling text. Bullet + flanking spaces — wide
     *  enough to read as a pause, and the dot gives an explicit "end of one cycle" marker. */
    private static final String SEPARATOR = "   •   ";

    /** Frame period in milliseconds. 16 ≈ 60 fps. */
    private static final long FRAME_PERIOD_MS = 16L;

    /** Pixels of horizontal motion per frame. Calibrated for ~75 px/s at 60 fps. */
    private static final float DEFAULT_SPEED_PX_PER_FRAME = 1.25f;

    @Nullable
    private CharSequence sourceText;
    private float scrollPx = 0f;
    private float loopWidthPx = 0f;
    private boolean scrolling = false;
    private boolean attached = false;
    private boolean marqueeEnabled = true;
    private float speedPxPerFrame = DEFAULT_SPEED_PX_PER_FRAME;

    private boolean insideTick = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!scrolling || !attached) return;
            scrollPx += speedPxPerFrame;
            if (loopWidthPx > 0f && scrollPx >= loopWidthPx) {
                // Wrap: the second copy of the text is now at the position the first occupied,
                // so resetting scrollX is visually invisible.
                scrollPx -= loopWidthPx;
            }
            insideTick = true;
            try {
                setScrollX(Math.round(scrollPx));
            } finally {
                insideTick = false;
            }
            postOnAnimationDelayed(this, FRAME_PERIOD_MS);
        }
    };

    @Override
    public void scrollTo(int x, int y) {
        // {@link TextView#onPreDraw} unconditionally calls {@link TextView#bringTextIntoView}
        // on every frame for non-editable text views. With LEFT/START gravity (our default)
        // that routine resets {@code scrollX} to 0 — fighting our marquee tick and producing
        // periodic visual jumps back to the start of the text. While the marquee is actively
        // scrolling, only our own tick gets to write to scrollX; any other caller is ignored.
        if (scrolling && !insideTick) return;
        super.scrollTo(x, y);
    }

    public MarqueeOutlineTextView(@NonNull Context context) {
        super(context);
        init();
    }

    public MarqueeOutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeOutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setHorizontallyScrolling(true);
        // We do our own overflow handling — ellipsize would clip the second copy.
        setEllipsize(null);
    }

    /**
     * Set the user-visible text. If it fits within the current available width, the text is
     * shown statically. If it overflows, the view duplicates the text with a separator and
     * starts the continuous scroll loop.
     */
    public void setMarqueeText(@Nullable CharSequence text) {
        CharSequence next = text == null ? "" : text;
        // Skip re-evaluation when the text is unchanged — otherwise every PlaybackState callback
        // (which fires on play/pause/seek/buffer events with the same subtitle) would reset the
        // scroll offset to zero, making the marquee restart mid-track every time the user seeks.
        if (TextUtils.equals(next, sourceText)) return;
        sourceText = next;
        evaluateAndUpdate();
    }

    /**
     * Enable / disable the marquee scroll behavior. When disabled, overflowing text is rendered
     * statically up to {@link #setMaxWidth(int) maxWidth} and cut off with an end ellipsis;
     * when enabled (the default), overflow triggers the continuous scroll loop.
     */
    public void setMarqueeEnabled(boolean enabled) {
        if (marqueeEnabled == enabled) return;
        marqueeEnabled = enabled;
        evaluateAndUpdate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Available width changed → re-decide static vs. scrolling. The first call here also
        // covers the bootstrap case where setMarqueeText() ran before measurement.
        evaluateAndUpdate();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        evaluateAndUpdate();
    }

    @Override
    public void setTypeface(@Nullable Typeface tf) {
        super.setTypeface(tf);
        evaluateAndUpdate();
    }

    @Override
    public void setMaxWidth(int maxPixels) {
        super.setMaxWidth(maxPixels);
        evaluateAndUpdate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (scrolling) {
            removeCallbacks(tick);
            postOnAnimation(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void evaluateAndUpdate() {
        CharSequence text = sourceText == null ? "" : sourceText;

        int maxWidthPx = getMaxWidth();
        boolean hasMaxWidth = maxWidthPx > 0 && maxWidthPx < Integer.MAX_VALUE;
        float contentWidth = getPaint().measureText(text, 0, text.length());
        int paddings = getPaddingLeft() + getPaddingRight();
        float naturalTotalWidth = contentWidth + paddings;
        boolean overflowing = hasMaxWidth && naturalTotalWidth > maxWidthPx + 0.5f;

        Mode desiredMode = overflowing
                ? (marqueeEnabled ? Mode.MARQUEE : Mode.ELLIPSIZE)
                : Mode.FITS;

        // Idempotent fast path: if the desired mode and the rendered TextView text already
        // match what they would become below, do nothing. This avoids resetting scrollPx and
        // re-running super.setText on every onSizeChanged / onMeasure cycle when nothing has
        // actually changed — which previously made the marquee snap visually mid-scroll.
        CharSequence desiredRenderedText = (desiredMode == Mode.MARQUEE)
                ? TextUtils.concat(text, SEPARATOR, text)
                : text;
        if (desiredMode == currentMode && TextUtils.equals(getText(), desiredRenderedText)) {
            // Already in the right state. If we're supposed to be scrolling and the tick
            // happened to be removed (e.g. by setMarqueeEnabled re-entrancy), re-arm it.
            if (scrolling && attached) {
                removeCallbacks(tick);
                postOnAnimation(tick);
            }
            return;
        }

        // Real transition — only here do we reset scroll state.
        removeCallbacks(tick);
        scrolling = false;
        scrollPx = 0f;
        setScrollX(0);
        currentMode = desiredMode;

        if (desiredMode == Mode.MARQUEE) {
            // Overflow + marquee enabled: render "text + separator + text" so the wrap point
            // is hidden by the already-visible second copy. {@link #onMeasure} clamps the
            // measured width to {@code maxWidth} in this state — that's the only reliable
            // way to cap it, because {@code setHorizontallyScrolling(true)} makes the
            // underlying TextView report the full natural text width from {@code onMeasure}
            // and silently ignore {@code setMaxWidth}.
            setEllipsize(null);
            // Re-enable horizontal scrolling — the ellipsize branch may have turned it off on
            // a previous evaluate, and without it setScrollX is silently clamped to 0 and the
            // text just slides off the right edge instead of wrapping to the second copy.
            setHorizontallyScrolling(true);
            // Compute the exact X position of the first glyph of the second copy in the shaped
            // combined string. {@code Paint.getRunAdvance} with the whole string as the
            // shaping context honours kerning across the [last separator char, first text char]
            // boundary — using {@code measureText(combined, 0, prefixLen)} instead would treat
            // the prefix as its own shaping context and miss that boundary kerning, leaving
            // the marquee wrap off by 1–3 px each loop (visible as a small periodic jump).
            int combinedLen = desiredRenderedText.length();
            int prefixLen = text.length() + SEPARATOR.length();
            loopWidthPx = getPaint().getRunAdvance(desiredRenderedText, 0, combinedLen,
                    0, combinedLen, false, prefixLen);
            super.setText(desiredRenderedText);
            scrolling = true;
            requestLayout();
            if (attached) {
                postOnAnimation(tick);
            }
        } else if (desiredMode == Mode.ELLIPSIZE) {
            // Overflow + marquee disabled: static render, cap at maxWidth with end ellipsis.
            // setHorizontallyScrolling(false) plus ellipsize=END lets the TextView handle the
            // cutoff itself; onMeasure still clamps the measured width because the original
            // setHorizontallyScrolling(true) from init() would otherwise report the natural
            // width.
            setHorizontallyScrolling(false);
            setEllipsize(android.text.TextUtils.TruncateAt.END);
            super.setText(desiredRenderedText);
            requestLayout();
        } else {
            // Fits — single render, no animation, no ellipsis needed, view grows naturally.
            setEllipsize(null);
            setHorizontallyScrolling(true);
            super.setText(desiredRenderedText);
            requestLayout();
        }
    }

    private enum Mode { UNSET, FITS, ELLIPSIZE, MARQUEE }
    private Mode currentMode = Mode.UNSET;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Re-measure under UNSPECIFIED so {@code setHorizontallyScrolling(true)} reports the
        // full natural text width without being squeezed by a competing-sibling AT_MOST cap
        // (overlay's LinearLayout horizontal with multiple bricks gives each child the
        // remaining-space cap, which would clip long media subtitles before our own logic
        // ever gets to choose between static render and scrolling).
        int unspecified = android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED);
        super.onMeasure(unspecified, heightMeasureSpec);
        int natural = getMeasuredWidth();
        int maxWidth = getMaxWidth();
        if (maxWidth > 0 && maxWidth < Integer.MAX_VALUE && natural > maxWidth) {
            natural = maxWidth;
        }
        setMeasuredDimension(natural, getMeasuredHeight());
    }
}
