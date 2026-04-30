/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class OutlineImageView extends AppCompatImageView {
    // Chamfer (3-4) distance transform: 1 px straight = 3 units, diagonal = 4 units.
    private static final int STRAIGHT = 3;
    private static final int DIAGONAL = 4;
    // Anti-aliasing band width (in distance units) for the outline edge.
    private static final int AA_BAND = STRAIGHT;

    private int outlineColor = Color.TRANSPARENT;
    private int outlineWidth = 0;

    private final Paint outlinePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int filterColor = 0;

    private Bitmap cachedOutline;
    private Drawable cachedDrawable;
    private int cachedDrawableState = -1;
    private int cachedWidth;
    private int cachedHeight;
    private int cachedOutlineWidth;

    public OutlineImageView(@NonNull Context context) {
        super(context);
    }

    public OutlineImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public OutlineImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOutlineColor(int color) {
        if (this.outlineColor != color) {
            this.outlineColor = color;
            invalidate();
        }
    }

    public void setOutlineWidth(int width) {
        width = Math.max(0, width);
        if (this.outlineWidth != width) {
            this.outlineWidth = width;
            invalidateOutlineCache();
            invalidate();
        }
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        invalidateOutlineCache();
    }

    @Override
    public void setImageResource(int resId) {
        super.setImageResource(resId);
        invalidateOutlineCache();
    }

    private void invalidateOutlineCache() {
        if (cachedOutline != null) {
            cachedOutline.recycle();
            cachedOutline = null;
        }
        cachedDrawable = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (outlineWidth > 0 && Color.alpha(outlineColor) > 0) {
            Bitmap outline = getOrBuildOutline();
            if (outline != null) {
                if (filterColor != outlineColor) {
                    outlinePaint.setColorFilter(new PorterDuffColorFilter(outlineColor, PorterDuff.Mode.SRC_IN));
                    filterColor = outlineColor;
                }
                canvas.drawBitmap(outline, 0, 0, outlinePaint);
            }
        }
        super.onDraw(canvas);
    }

    @Nullable
    private Bitmap getOrBuildOutline() {
        int w = getWidth();
        int h = getHeight();
        Drawable drawable = getDrawable();
        if (drawable == null || w <= 0 || h <= 0) {
            return null;
        }

        int drawableState = System.identityHashCode(drawable.getCurrent());
        if (cachedOutline != null
                && !cachedOutline.isRecycled()
                && cachedDrawable == drawable
                && cachedDrawableState == drawableState
                && cachedWidth == w
                && cachedHeight == h
                && cachedOutlineWidth == outlineWidth) {
            return cachedOutline;
        }

        // Render the icon (with ImageView scaling/padding) into a bitmap and read its alpha channel.
        Bitmap rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas renderCanvas = new Canvas(rendered);
        super.onDraw(renderCanvas);

        int[] pixels = new int[w * h];
        rendered.getPixels(pixels, 0, w, 0, 0, w, h);
        rendered.recycle();

        int[] alphas = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            alphas[i] = (pixels[i] >>> 24) & 0xFF;
        }

        int[] dilated = dilateByDistance(alphas, w, h, outlineWidth);

        // Pack as opaque-white pixels with dilated alpha; the actual color is applied via ColorFilter.
        for (int i = 0; i < dilated.length; i++) {
            pixels[i] = (dilated[i] << 24) | 0x00FFFFFF;
        }

        Bitmap outline = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        outline.setPixels(pixels, 0, w, 0, 0, w, h);

        if (cachedOutline != null && !cachedOutline.isRecycled()) {
            cachedOutline.recycle();
        }
        cachedOutline = outline;
        cachedDrawable = drawable;
        cachedDrawableState = drawableState;
        cachedWidth = w;
        cachedHeight = h;
        cachedOutlineWidth = outlineWidth;

        return cachedOutline;
    }

    /**
     * Two-pass chamfer (3-4) distance transform on the alpha mask, then threshold at radius
     * with a soft band for anti-aliasing. Result is the dilated alpha mask with crisp,
     * approximately-circular outline.
     */
    private static int[] dilateByDistance(int[] alphas, int w, int h, int radius) {
        int infinity = Integer.MAX_VALUE / 2;
        int[] dist = new int[w * h];
        for (int i = 0; i < dist.length; i++) {
            dist[i] = alphas[i] >= 128 ? 0 : infinity;
        }

        // Forward pass: top-left to bottom-right.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int d = dist[idx];
                if (d == 0) continue;
                if (x > 0) {
                    int v = dist[idx - 1] + STRAIGHT;
                    if (v < d) d = v;
                }
                if (y > 0) {
                    int v = dist[idx - w] + STRAIGHT;
                    if (v < d) d = v;
                    if (x > 0) {
                        int dl = dist[idx - w - 1] + DIAGONAL;
                        if (dl < d) d = dl;
                    }
                    if (x < w - 1) {
                        int dr = dist[idx - w + 1] + DIAGONAL;
                        if (dr < d) d = dr;
                    }
                }
                dist[idx] = d;
            }
        }

        // Backward pass: bottom-right to top-left.
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int idx = y * w + x;
                int d = dist[idx];
                if (d == 0) continue;
                if (x < w - 1) {
                    int v = dist[idx + 1] + STRAIGHT;
                    if (v < d) d = v;
                }
                if (y < h - 1) {
                    int v = dist[idx + w] + STRAIGHT;
                    if (v < d) d = v;
                    if (x > 0) {
                        int dl = dist[idx + w - 1] + DIAGONAL;
                        if (dl < d) d = dl;
                    }
                    if (x < w - 1) {
                        int dr = dist[idx + w + 1] + DIAGONAL;
                        if (dr < d) d = dr;
                    }
                }
                dist[idx] = d;
            }
        }

        int maxDist = radius * STRAIGHT;
        int[] result = new int[w * h];
        for (int i = 0; i < result.length; i++) {
            int d = dist[i];
            if (d <= maxDist) {
                result[i] = 0xFF;
            } else if (d <= maxDist + AA_BAND) {
                int a = 0xFF - (d - maxDist) * 0xFF / AA_BAND;
                result[i] = Math.max(0, Math.min(0xFF, a));
            }
        }
        return result;
    }
}
