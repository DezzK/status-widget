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
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Minimal horizontal progress bar used by the media brick. Draws a translucent track and a solid
 * foreground rectangle filling {@code progress} fraction of the width. Kept as a custom view (vs
 * the platform {@link android.widget.ProgressBar}) so we can:
 * <ul>
 *   <li>render at a single thin pixel height without theme-imposed minimums,</li>
 *   <li>tint without juggling {@code progressTint}/{@code progressBackgroundTint} APIs that
 *       behave differently across Android versions,</li>
 *   <li>avoid the indeterminate animator and other features we don't use.</li>
 * </ul>
 */
public class MediaProgressBar extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress = 0f;

    public MediaProgressBar(@NonNull Context context) {
        super(context);
        init();
    }

    public MediaProgressBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MediaProgressBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.FILL);
        progressPaint.setStyle(Paint.Style.FILL);
        setColor(Color.WHITE);
    }

    /** Set both progress fill color and the (auto-derived translucent) track color in one call. */
    public void setColor(int colorArgb) {
        if (progressPaint.getColor() == colorArgb) return;
        progressPaint.setColor(colorArgb);
        // Track = 25% alpha of the foreground color, so the bar reads consistently against any
        // background. Keeping RGB identical means the user only has to pick one colour.
        int alpha = Math.max(0, (Color.alpha(colorArgb) * 64) / 255);
        trackPaint.setColor(Color.argb(alpha,
                Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb)));
        invalidate();
    }

    /** {@code progress} clamped into [0, 1]. {@code Float.NaN} is treated as zero. */
    public void setProgress(float p) {
        if (Float.isNaN(p)) p = 0f;
        p = Math.max(0f, Math.min(1f, p));
        if (p == progress) return;
        progress = p;
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);
        if (progress > 0f) {
            rect.set(0, 0, w * progress, h);
            canvas.drawRoundRect(rect, radius, radius, progressPaint);
        }
    }
}
