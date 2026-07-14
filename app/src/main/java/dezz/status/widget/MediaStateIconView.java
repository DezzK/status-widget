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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Playback-state indicator of the media brick: a right-pointing triangle while playing, two
 * vertical bars while paused. Both shapes are drawn, not typed.
 * <p>
 * The pause shape used to be faked with text glyphs, and no glyph does the job: every codepoint
 * that actually means "pause" (U+23F8 and friends) defaults to emoji presentation, so Android's
 * emoji font takes it over and paints a coloured icon that ignores the widget's text colour,
 * outline and opacity; the typographic bars that stay text-rendered (U+275A and such) have
 * per-font weights and side bearings and don't pair with the triangle. Drawing both shapes here
 * gives one look regardless of the font the user picked for the brick.
 * <p>
 * Everything is derived from {@link #setTextSizePx(float) the text size of the line that hosts the
 * icon}, so the indicator scales with the brick's font-size sliders, and it takes the same
 * colour/outline/alpha treatment as that line — including the day/night (and forced light/dark)
 * widget themes, whose resolved colours are pushed in from {@code WidgetService}.
 * <p>
 * The measured box is state-independent — it fits the wider of the two shapes and each shape is
 * centred in it — so flipping play↔pause is a pure repaint: no relayout, no horizontal nudge of
 * the text next to it. That matters because players republish their {@code PlaybackState} up to
 * once a second.
 */
public class MediaStateIconView extends View {
    /** Glyph height as a fraction of the host line's text size. Roboto's cap height is ~0.71em;
     *  a solid triangle carries more visual mass than a letter, so it sits a bit below that. */
    private static final float GLYPH_HEIGHT_RATIO = 0.62f;
    /** Box width as a fraction of the glyph height — fits the wider of the two shapes. */
    private static final float BOX_WIDTH_RATIO = 0.88f;
    /** Play triangle width, relative to the glyph height (roughly equilateral). */
    private static final float PLAY_WIDTH_RATIO = 0.86f;
    /** Pause bar width and the gap between the two bars, relative to the glyph height. */
    private static final float PAUSE_BAR_RATIO = 0.29f;
    private static final float PAUSE_GAP_RATIO = 0.26f;
    /** Corner rounding of both shapes, relative to the glyph height. Matches the rounded ends of
     *  the progress bar right below. */
    private static final float CORNER_RATIO = 0.08f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapePath = new Path();

    private boolean paused = false;
    private float textSizePx = 20f;
    private float outlineWidth = 0f;
    /** Geometry is rebuilt lazily so a size/state change costs one rebuild, not one per draw. */
    private boolean geometryDirty = true;
    @Nullable
    private PathEffect cornerEffect;
    private float cornerRadius = -1f;

    public MediaStateIconView(@NonNull Context context) {
        super(context);
        init();
    }

    public MediaStateIconView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MediaStateIconView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        // Round join/cap so the triangle's apex doesn't grow a spike at wide outline widths —
        // the same softening the outlined text gets from its stroke pass.
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        outlinePaint.setColor(Color.TRANSPARENT);
    }

    /** {@code true} → two bars (paused), {@code false} → triangle (playing). */
    public void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        geometryDirty = true;
        invalidate();
    }

    /** Text size (px) of the line hosting the icon — the icon scales itself against it. */
    public void setTextSizePx(float px) {
        if (textSizePx == px) return;
        textSizePx = px;
        geometryDirty = true;
        requestLayout();
        invalidate();
    }

    public void setIconColor(int color) {
        if (fillPaint.getColor() == color) return;
        fillPaint.setColor(color);
        invalidate();
    }

    public void setOutlineColor(int color) {
        if (outlinePaint.getColor() == color) return;
        outlinePaint.setColor(color);
        invalidate();
    }

    /** Outline stroke width (px). Centred on the shape, so half of it bleeds outwards — the
     *  measured box reserves that bleed, otherwise the parent row would clip it. */
    public void setOutlineWidth(float width) {
        if (outlineWidth == width) return;
        outlineWidth = width;
        outlinePaint.setStrokeWidth(width);
        geometryDirty = true;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float glyphHeight = textSizePx * GLYPH_HEIGHT_RATIO;
        int width = (int) Math.ceil(glyphHeight * BOX_WIDTH_RATIO + outlineWidth);
        int height = (int) Math.ceil(glyphHeight + outlineWidth);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometryDirty = true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (geometryDirty) rebuildGeometry();
        if (outlineWidth > 0f && Color.alpha(outlinePaint.getColor()) > 0) {
            canvas.drawPath(shapePath, outlinePaint);
        }
        canvas.drawPath(shapePath, fillPaint);
    }

    /**
     * Rebuild the current shape, centred in the measured box. Both shapes go into a single
     * {@link Path} so the outline pass is one stroke of the whole silhouette (drawing the two
     * pause bars separately would be identical, but this keeps the draw path uniform).
     */
    private void rebuildGeometry() {
        geometryDirty = false;
        float glyphHeight = textSizePx * GLYPH_HEIGHT_RATIO;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float top = centerY - glyphHeight / 2f;
        float bottom = centerY + glyphHeight / 2f;

        float corner = glyphHeight * CORNER_RATIO;
        if (cornerEffect == null || cornerRadius != corner) {
            cornerRadius = corner;
            cornerEffect = new CornerPathEffect(corner);
            fillPaint.setPathEffect(cornerEffect);
            outlinePaint.setPathEffect(cornerEffect);
        }

        shapePath.reset();
        if (paused) {
            float barWidth = glyphHeight * PAUSE_BAR_RATIO;
            float gap = glyphHeight * PAUSE_GAP_RATIO;
            float left = centerX - (barWidth * 2f + gap) / 2f;
            shapePath.addRect(left, top, left + barWidth, bottom, Path.Direction.CW);
            float secondLeft = left + barWidth + gap;
            shapePath.addRect(secondLeft, top, secondLeft + barWidth, bottom, Path.Direction.CW);
        } else {
            float playWidth = glyphHeight * PLAY_WIDTH_RATIO;
            float left = centerX - playWidth / 2f;
            shapePath.moveTo(left, top);
            shapePath.lineTo(left + playWidth, centerY);
            shapePath.lineTo(left, bottom);
            shapePath.close();
        }
    }
}
