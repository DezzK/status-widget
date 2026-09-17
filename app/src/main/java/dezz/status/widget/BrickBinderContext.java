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

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.ArrayRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Everything a brick-card binder needs that is not one of its own views: the activity, the
 * preferences, a way to push a change to the running widget, and the handful of view-binding
 * idioms that both the shared card and the per-type blocks use.
 *
 * <p>One instance per {@link BrickListAdapter}, handed to every {@link BrickBlockBinder} at
 * construction so a block binder's constructor is {@code (ctx, root, typedPrefs)} and nothing
 * else.
 *
 * <p>Note on {@link #bindIntSlider}: it deliberately differs from {@link ViewBinder#bindSlider}
 * by writing the pref on programmatic changes too, not just {@code fromUser} ones. Unifying the
 * two is a behaviour change and is left for its own commit.
 */
final class BrickBinderContext {
    final AppCompatActivity activity;
    final Preferences prefs;
    private final Runnable serviceNotifier;

    BrickBinderContext(AppCompatActivity activity, Preferences prefs, Runnable serviceNotifier) {
        this.activity = activity;
        this.prefs = prefs;
        this.serviceNotifier = serviceNotifier;
    }

    void notifyService() {
        serviceNotifier.run();
    }

    void bindIntSlider(Slider slider, Preferences.Int pref, LabelFormatter formatter) {
        float current = clamp(pref.get(), slider.getValueFrom(), slider.getValueTo());
        slider.setValue(current);
        slider.setLabelFormatter(formatter);
        // Permanent value-label (see findValueLabel) replaces the floating Material bubble on
        // touch — that bubble appears right under the user's finger and is the main complaint
        // about Material 3 sliders on car head units.
        TextView valueLabel = ViewBinder.findValueLabel(slider);
        if (valueLabel != null) {
            slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
            valueLabel.setText(formatter.getFormattedValue(slider.getValue()));
            valueLabel.setOnClickListener(v -> showNumericInputDialog(slider, pref, formatter));
        }
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (valueLabel != null) {
                valueLabel.setText(formatter.getFormattedValue(value));
            }
            pref.set((int) value);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    private void showNumericInputDialog(Slider slider, Preferences.Int pref,
                                        LabelFormatter formatter) {
        final int min = (int) slider.getValueFrom();
        final int max = (int) slider.getValueTo();

        android.widget.EditText input = new android.widget.EditText(activity);
        input.setInputType(min < 0
                ? (android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)
                : android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(pref.get()));
        input.setSelection(input.getText().length());
        input.setHint(activity.getString(R.string.value_edit_range, min, max));

        int pad = activity.getResources().getDimensionPixelSize(R.dimen.optionsMargin);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        frame.addView(input, lp);

        CharSequence title = slider.getContentDescription();
        if (title == null || title.length() == 0) {
            title = activity.getString(R.string.value_edit_title);
        }

        new androidx.appcompat.app.AlertDialog.Builder(activity)
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
                    slider.setValue(clamped);
                    pref.set(clamped);
                    if (WidgetService.isRunning()) {
                        WidgetService.getInstance().applyPreferences();
                    }
                })
                .show();
    }

    /**
     * Wire a font-family dropdown to a {@link Preferences.Str} pref carrying {@link Fonts} keys.
     * Reused for the shared card's font block and for the media source / title lines, which need
     * the same UX but bind different prefs.
     */
    void bindFontFamilyDropdown(MaterialAutoCompleteTextView dropdown,
                                Preferences.Str familyPref) {
        String[] labels = new String[Fonts.ALL.size()];
        for (int i = 0; i < Fonts.ALL.size(); i++) {
            labels[i] = activity.getString(Fonts.ALL.get(i).labelRes);
        }
        dropdown.setAdapter(dropdownAdapter(labels));
        int currentIdx = 0;
        String currentKey = familyPref.get();
        for (int i = 0; i < Fonts.ALL.size(); i++) {
            if (Fonts.ALL.get(i).key.equals(currentKey)) {
                currentIdx = i;
                break;
            }
        }
        dropdown.setText(labels[currentIdx], false);
        dropdown.setOnItemClickListener((parent, view, position, id) ->
                setAndNotify(familyPref, Fonts.ALL.get(position).key));
    }

    void bindFontStyleToggles(MaterialButtonToggleGroup group,
                              MaterialButton boldButton, MaterialButton italicButton,
                              Preferences.Bool boldPref, Preferences.Bool italicPref) {
        group.clearOnButtonCheckedListeners();
        // Set checked state BEFORE attaching the listener so seeding doesn't fire it.
        boldButton.setChecked(boldPref.get());
        italicButton.setChecked(italicPref.get());
        final int boldId = boldButton.getId();
        final int italicId = italicButton.getId();
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (checkedId == boldId) {
                setAndNotify(boldPref, isChecked);
            } else if (checkedId == italicId) {
                setAndNotify(italicPref, isChecked);
            }
        });
    }

    /** Reusable Start/Center/End alignment dropdown bound to an int 0..2 preference. */
    void bindAlignmentDropdown(MaterialAutoCompleteTextView dropdown, Preferences.Int pref) {
        bindIntDropdown(dropdown, R.array.calendar_alignment_types, pref, null);
    }

    /**
     * Dropdown over a string array whose selected index is stored in an int preference,
     * optionally running {@code onChange} after the write.
     */
    void bindIntDropdown(MaterialAutoCompleteTextView dropdown, @ArrayRes int arrayRes,
                         Preferences.Int pref, @Nullable Runnable onChange) {
        String[] options = activity.getResources().getStringArray(arrayRes);
        dropdown.setAdapter(dropdownAdapter(options));
        int current = clamp(pref.get(), 0, options.length - 1);
        dropdown.setText(options[current], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            pref.set(position);
            if (onChange != null) onChange.run();
            notifyService();
        });
    }

    /**
     * Status-bar placement dropdown. Only meaningful in status-bar mode, so the whole field is
     * collapsed otherwise — for a brick whose block pairs it with a text-alignment dropdown in a
     * weighted row, that lets the sibling stretch to the full width.
     */
    void bindStatusAlignmentDropdown(Preferences.Int pref, TextInputLayout layout,
                                     MaterialAutoCompleteTextView dropdown) {
        boolean statusBar = prefs.widgetMode.get() == 1;
        layout.setVisibility(statusBar ? View.VISIBLE : View.GONE);
        if (!statusBar) return;
        String[] items = activity.getResources().getStringArray(R.array.brick_status_alignments);
        dropdown.setAdapter(dropdownAdapter(items));
        int current = clamp(pref.get(), 0, items.length - 1);
        dropdown.setText(items[current], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            pref.set(position);
            notifyService();
        });
    }

    /** Bind a switch to a boolean pref, optionally running {@code onChange} after the write. */
    void bindSwitch(android.widget.CompoundButton switchView, Preferences.Bool pref,
                    Runnable onChange) {
        switchView.setChecked(pref.get());
        switchView.setOnCheckedChangeListener((v, checked) -> {
            pref.set(checked);
            if (onChange != null) onChange.run();
            notifyService();
        });
    }

    void setAndNotify(Preferences.Bool pref, boolean v) {
        pref.set(v);
        notifyService();
    }

    void setAndNotify(Preferences.Str pref, String v) {
        pref.set(v);
        notifyService();
    }

    LabelFormatter sizeFormatter() {
        return value -> activity.getString(R.string.size_value_format, (int) value);
    }

    LabelFormatter plainFormatter() {
        return value -> activity.getString(R.string.color_component_value_format, (int) value);
    }

    LabelFormatter offsetFormatter() {
        return value -> {
            int v = (int) value;
            return (v > 0 ? "+" : "") + activity.getString(R.string.size_value_format, v);
        };
    }

    private ArrayAdapter<String> dropdownAdapter(String[] items) {
        return new ArrayAdapter<>(
                activity,
                com.google.android.material.R.layout.m3_auto_complete_simple_item,
                items);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
