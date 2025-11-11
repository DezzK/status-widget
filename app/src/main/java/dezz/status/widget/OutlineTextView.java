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