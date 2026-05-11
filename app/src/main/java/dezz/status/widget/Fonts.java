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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Catalogue of font families available to text bricks. Mixes one bundled font (Roboto Condensed —
 * used historically and kept as the default) with a small set of widely-available Android system
 * families resolved via {@link Typeface#create(String, int)}.
 *
 * Families are referenced by a stable string key stored in {@link Preferences}, so renaming the
 * display labels never breaks saved preferences.
 */
public final class Fonts {
    /** Stable storage key for the default family — keep in sync with {@link #DEFAULT}. */
    public static final String DEFAULT_KEY = "roboto_condensed";

    public static final class Family {
        /** Stored in SharedPreferences — never localised, never renamed. */
        public final String key;
        @StringRes
        public final int labelRes;
        /** {@code 0} for system families. */
        private final int fontRes;
        /** {@code null} for bundled families. */
        @Nullable
        private final String systemName;

        Family(String key, @StringRes int labelRes, int fontRes, @Nullable String systemName) {
            this.key = key;
            this.labelRes = labelRes;
            this.fontRes = fontRes;
            this.systemName = systemName;
        }

        @NonNull
        Typeface base(@NonNull Context context) {
            if (fontRes != 0) {
                Typeface tf = ResourcesCompat.getFont(context, fontRes);
                if (tf != null) return tf;
            }
            if (systemName != null) {
                return Typeface.create(systemName, Typeface.NORMAL);
            }
            return Typeface.DEFAULT;
        }
    }

    public static final Family DEFAULT = new Family(
            DEFAULT_KEY, R.string.font_roboto_condensed, R.font.roboto_condensed_medium, null);

    public static final List<Family> ALL = Collections.unmodifiableList(Arrays.asList(
            DEFAULT,
            new Family("sans-serif", R.string.font_sans_serif, 0, "sans-serif"),
            new Family("sans-serif-condensed", R.string.font_sans_serif_condensed, 0, "sans-serif-condensed"),
            new Family("sans-serif-light", R.string.font_sans_serif_light, 0, "sans-serif-light"),
            new Family("sans-serif-medium", R.string.font_sans_serif_medium, 0, "sans-serif-medium"),
            new Family("serif", R.string.font_serif, 0, "serif"),
            new Family("monospace", R.string.font_monospace, 0, "monospace")
    ));

    @NonNull
    public static Family findByKey(@Nullable String key) {
        if (key != null) {
            for (Family f : ALL) {
                if (f.key.equals(key)) return f;
            }
        }
        return DEFAULT;
    }

    /**
     * Resolves a family key + bold/italic flags into a styled {@link Typeface}. Falls back to the
     * default family if {@code key} is unknown so a typo in stored prefs can't crash the widget.
     */
    @NonNull
    public static Typeface resolve(@NonNull Context context, @Nullable String key,
                                   boolean bold, boolean italic) {
        Typeface base = findByKey(key).base(context);
        int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
        if (style == Typeface.NORMAL) return base;
        return Typeface.create(base, style);
    }

    private Fonts() {}
}
