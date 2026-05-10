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
import android.widget.CompoundButton;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

public final class ViewBinder {
    private final Context context;

    public ViewBinder(Context context) {
        this.context = context;
    }

    public void bindCheckbox(CompoundButton checkbox, Preferences.Bool preference) {
        checkbox.setChecked(preference.get());
        checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preference.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    public void bindColorComponentSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference, value -> String.format(context.getString(R.string.color_component_value_format), (int) value));
    }

    public void bindSizeSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference, value -> String.format(context.getString(R.string.size_value_format), (int) value));
    }

    public void bindOffsetSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference, value -> {
            int v = (int) value;
            return String.format((v > 0 ? "+" : "") + context.getString(R.string.size_value_format), v);
        });
    }

    public void bindPercentSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference, value -> String.format(context.getString(R.string.percent_value_format), (int) value));
    }

    public void bindSlider(Slider slider, Preferences.Int preference, LabelFormatter formatter) {
        float current = clamp(preference.get(), slider.getValueFrom(), slider.getValueTo());
        slider.setValue(current);
        slider.setLabelFormatter(formatter);
        slider.addOnChangeListener((s, value, fromUser) -> {
            // Programmatic setValue (e.g. live-updating the position slider while the widget is
            // being dragged) shouldn't write back to the pref or kick applyPreferences().
            if (!fromUser) return;
            preference.set((int) value);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
