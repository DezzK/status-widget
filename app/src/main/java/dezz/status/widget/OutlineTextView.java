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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

public class OutlineTextView extends AppCompatTextView {
    private static final float DEFAULT_OUTLINE_WIDTH = 0F;

    private boolean isDrawing = false;
    private int outlineColor = 0;
    private float outlineWidth = DEFAULT_OUTLINE_WIDTH;

    public OutlineTextView(@NonNull Context context) {
        super(context);
    }

    public OutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public OutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public int getOutlineColor() {
        return this.outlineColor;
    }

    public void setOutlineColor(int color) {
        this.outlineColor = color;
        invalidate();
    }

    public float getOutlineWidth() {
        return outlineWidth;
    }

    public void setOutlineWidth(float outlineWidth) {
        this.outlineWidth = outlineWidth;
        invalidate();
    }

    @Override
    public void invalidate() {
        if (!isDrawing) {
            super.invalidate();
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (outlineWidth <= 0) {
            super.onDraw(canvas);
            return;
        }

        int textColor = getCurrentTextColor();
        isDrawing = true;
        try {
            TextPaint paint = getPaint();

            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(outlineWidth);
            setTextColor(outlineColor);

            super.onDraw(canvas);

            paint.setStyle(Paint.Style.FILL);
            setTextColor(textColor);

            super.onDraw(canvas);
        } finally {
            isDrawing = false;
        }
    }
}