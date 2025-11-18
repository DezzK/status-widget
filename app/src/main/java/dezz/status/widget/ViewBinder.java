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
