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
import android.content.res.Resources;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

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
        bindSlider(slider, preference,
                value -> String.format(context.getString(R.string.color_component_value_format), (int) value));
    }

    public void bindSizeSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference,
                value -> String.format(context.getString(R.string.size_value_format), (int) value));
    }

    public void bindOffsetSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference, value -> {
            int v = (int) value;
            return String.format((v > 0 ? "+" : "") + context.getString(R.string.size_value_format), v);
        });
    }

    public void bindPercentSlider(Slider slider, Preferences.Int preference) {
        bindSlider(slider, preference,
                value -> String.format(context.getString(R.string.percent_value_format), (int) value));
    }

    public void bindSlider(Slider slider, Preferences.Int preference, LabelFormatter formatter) {
        float current = clamp(preference.get(), slider.getValueFrom(), slider.getValueTo());
        slider.setValue(current);
        slider.setLabelFormatter(formatter);
        // We display the current value in a permanent label (see findValueLabel below) so the
        // floating Material bubble — which appears under the user's finger on touch — is just
        // visual noise. Hide it where a value-label is present; otherwise keep the default
        // tooltip behaviour so untouched layouts don't regress.
        TextView valueLabel = findValueLabel(slider);
        if (valueLabel != null) {
            slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
            valueLabel.setText(formatter.getFormattedValue(slider.getValue()));
            valueLabel.setOnClickListener(v -> showNumericInputDialog(slider, preference, formatter));
        }
        slider.addOnChangeListener((s, value, fromUser) -> {
            // Programmatic setValue (e.g. live-updating the position slider while the widget is
            // being dragged) shouldn't write back to the pref or kick applyPreferences().
            if (valueLabel != null) {
                valueLabel.setText(formatter.getFormattedValue(value));
            }
            if (!fromUser) return;
            preference.set((int) value);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    /**
     * By convention, every {@link Slider} that should display a permanent value-label has a
     * sibling {@link TextView} with id {@code <sliderId>Value}. Walks up the view tree from the
     * slider so the lookup still works when sliders are nested inside per-column wrappers.
     */
    @Nullable
    private static TextView findValueLabel(Slider slider) {
        int sliderId = slider.getId();
        if (sliderId == View.NO_ID) return null;
        Resources res = slider.getResources();
        String sliderName;
        try {
            sliderName = res.getResourceEntryName(sliderId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
        int valueId = res.getIdentifier(
                sliderName + "Value", "id", slider.getContext().getPackageName());
        if (valueId == 0) return null;
        ViewGroup parent = (slider.getParent() instanceof ViewGroup)
                ? (ViewGroup) slider.getParent() : null;
        while (parent != null) {
            View found = parent.findViewById(valueId);
            if (found instanceof TextView) return (TextView) found;
            parent = (parent.getParent() instanceof ViewGroup)
                    ? (ViewGroup) parent.getParent() : null;
        }
        return null;
    }

    /**
     * Tap-on-value editor: lets the user type an exact integer instead of nudging the slider.
     * On confirm the value is clamped to the slider's bounds and pushed through the same path
     * the slider would take — the existing change listener handles persistence and service.
     */
    private void showNumericInputDialog(Slider slider, Preferences.Int preference,
                                        LabelFormatter formatter) {
        final int min = (int) slider.getValueFrom();
        final int max = (int) slider.getValueTo();
        final int current = preference.get();

        EditText input = new EditText(context);
        input.setInputType(min < 0
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED)
                : InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());
        input.setHint(context.getString(R.string.value_edit_range, min, max));

        int pad = context.getResources().getDimensionPixelSize(R.dimen.optionsMargin);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        frame.addView(input, lp);

        CharSequence title = slider.getContentDescription();
        if (title == null || title.length() == 0) {
            title = context.getString(R.string.value_edit_title);
        }

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String text = input.getText().toString().trim();
                    int parsed;
                    try {
                        parsed = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        return;
                    }
                    int clamped = Math.max(min, Math.min(max, parsed));
                    // setValue triggers OnChangeListener (with fromUser=false), which keeps
                    // the value-label in sync; persist explicitly so the !fromUser branch
                    // doesn't swallow the write.
                    slider.setValue(clamped);
                    preference.set(clamped);
                    if (WidgetService.isRunning()) {
                        WidgetService.getInstance().applyPreferences();
                    }
                })
                .show();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
