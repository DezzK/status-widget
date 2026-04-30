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
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

public final class ViewBinder {
    private final Context context;

    public ViewBinder(Context context) {
        this.context = context;
    }

    public void bindCheckbox(SwitchCompat checkbox, Preferences.Bool preference) {
        checkbox.setChecked(preference.get());
        checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preference.set(isChecked);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
    }

    public void bindColorComponentSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format(context.getString(R.string.color_component_value_format), value));
    }

    public void bindPercentSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format(context.getString(R.string.percent_value_format), value));
    }

    public void bindSizeSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format(context.getString(R.string.size_value_format), value));
    }

    public void bindOffsetSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference) {
        bindSeekbar(seekBar, valueText, preference, value -> String.format((value > 0 ? "+" : "") + context.getString(R.string.size_value_format), value));
    }

    public void bindSeekbar(SeekBar seekBar, TextView valueText, Preferences.Int preference, ValueTextFormatter formatter) {
        int progress = preference.get();
        seekBar.setProgress(progress);
        valueText.setText(formatter.formatValueText(progress));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                preference.set(progress);
                valueText.setText(formatter.formatValueText(progress));
                if (WidgetService.isRunning()) {
                    WidgetService.getInstance().applyPreferences();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    public interface ValueTextFormatter {
        String formatValueText(int progress);
    }
}
