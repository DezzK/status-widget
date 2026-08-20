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

import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * A brick that draws one line of text. Everything the text bricks share lives here: the font,
 * outline, offset and opacity pass, and the line-height estimate the row's minimum height is
 * built from.
 */
abstract class TextRenderBrick extends RenderBrick {

    private final Preferences.TextBrickPrefs prefs;
    @IdRes
    private final int viewId;
    protected OutlineTextView text;

    protected TextRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                              @NonNull Preferences.TextBrickPrefs prefs, @IdRes int viewId) {
        super(host, type);
        this.prefs = prefs;
        this.viewId = viewId;
    }

    @Override
    void bind(@NonNull OverlayStatusWidgetBinding binding) {
        text = binding.getRoot().findViewById(viewId);
    }

    @NonNull
    @Override
    View view() {
        return text;
    }

    @Override
    void applySettings() {
        if (text == null) return;
        // Owning an alignment pref is what makes a text brick alignable, so the gravity is
        // applied here rather than per brick — otherwise a new AlignedTextBrickPrefs subclass
        // would get a live pref and a bound dropdown that render nothing.
        if (prefs instanceof Preferences.AlignedTextBrickPrefs) {
            text.setGravity(horizontalGravity(
                    ((Preferences.AlignedTextBrickPrefs) prefs).alignment.get()));
        }
        text.setTextColor(ContextCompat.getColor(host.themed(), R.color.text_primary));
        text.setOutlineColor(outlineColor(host.themed(), prefs.outlineAlpha.get()));
        text.setOutlineWidth(prefs.outlineWidth.get());
        Typeface typeface = Fonts.resolve(host.context(), prefs.fontFamily.get(),
                prefs.fontBold.get(), prefs.fontItalic.get());
        text.setTypeface(typeface);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.fontSize.get());
        text.setTranslationY(prefs.adjustY.get());
        text.setAlpha(prefs.contentAlpha.get() / 255f);
        applyHorizontalMargins(text, prefs.marginStart.get(), prefs.marginEnd.get());
    }

    @Override
    int minHeight() {
        return lineHeight() * lineCount();
    }

    /** How many lines this brick renders at its tallest. One unless the brick says otherwise. */
    protected int lineCount() {
        return 1;
    }

    /**
     * The height one line reserves, from the view's own paint at the configured size.
     *
     * <p>All text views in the widget set {@code includeFontPadding=false}, so a line reserves
     * exactly ascent..descent — and {@code StaticLayout} takes those from {@code getFontMetricsInt},
     * so read the same integers rather than rounding the float pair and landing a pixel off.
     */
    protected final int lineHeight() {
        return lineHeight(text, prefs.fontSize.get());
    }

    /** Shared with the media brick, whose lines live inside a container rather than on a brick. */
    static int lineHeight(@NonNull OutlineTextView view, int fontSizePx) {
        Paint p = new Paint(view.getPaint());
        p.setTextSize(fontSizePx);
        Paint.FontMetricsInt fm = p.getFontMetricsInt();
        return fm.descent - fm.ascent;
    }

    /** Maps the shared 0/1/2 = start/center/end alignment prefs onto a {@link Gravity}. */
    private static int horizontalGravity(int alignment) {
        switch (alignment) {
            case 1:
                return Gravity.CENTER_HORIZONTAL;
            case 2:
                return Gravity.END;
            default:
                return Gravity.START;
        }
    }

    /** Write text only when it actually differs — {@code setText} relayouts even for equal text. */
    protected final void setTextIfChanged(CharSequence value) {
        if (text != null && !value.toString().contentEquals(text.getText())) {
            text.setText(value);
        }
    }

    protected final boolean hasView() {
        return text != null;
    }
}
