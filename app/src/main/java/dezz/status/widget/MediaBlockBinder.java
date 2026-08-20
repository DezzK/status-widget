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

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * Binds {@code brick_block_media.xml} — by far the largest block, four sections deep: Общие,
 * Источник, Композиция, Длительность.
 *
 * <p>Source, title and duration each get their own font, outline and opacity, which is why media
 * drops the shared card's size / outline / opacity rows and its font block. It keeps the shared
 * margin row, the vertical-offset column and the status-alignment dropdown — those it used to
 * duplicate inside this block, against the very same preferences.
 */
final class MediaBlockBinder extends BrickBlockBinder {
    private final Preferences.MediaBrickPrefs p;

    // Общие
    private final MaterialSwitch showSource;
    private final MaterialSwitch titleFirst;
    private final MaterialSwitch progressBarEnabled;
    private final MaterialSwitch showPlaybackState;
    private final Slider maxWidthSlider;
    private final Slider lineGapSlider;
    // Источник
    private final LinearLayout sourceSection;
    private final Slider sourceFontSizeSlider;
    private final Slider sourceOutlineAlphaSlider;
    private final Slider sourceOutlineWidthSlider;
    private final Slider sourceContentAlphaSlider;
    private final MaterialAutoCompleteTextView sourceFontFamilyDropdown;
    private final MaterialButtonToggleGroup sourceFontStyleGroup;
    private final MaterialButton sourceFontBold;
    private final MaterialButton sourceFontItalic;
    private final MaterialAutoCompleteTextView sourceAlignmentDropdown;
    // Композиция
    private final MaterialSwitch marqueeEnabled;
    private final Slider titleFontSizeSlider;
    private final Slider titleOutlineAlphaSlider;
    private final Slider titleOutlineWidthSlider;
    private final Slider titleContentAlphaSlider;
    private final MaterialAutoCompleteTextView titleFontFamilyDropdown;
    private final MaterialButtonToggleGroup titleFontStyleGroup;
    private final MaterialButton titleFontBold;
    private final MaterialButton titleFontItalic;
    private final MaterialAutoCompleteTextView titleAlignmentDropdown;
    // Длительность
    private final MaterialSwitch showDuration;
    private final LinearLayout durationSection;
    private final Slider durationFontSizeSlider;
    private final Slider durationContentAlphaSlider;
    private final Slider durationOutlineAlphaSlider;
    private final Slider durationOutlineWidthSlider;

    private final MaterialButton permissionButton;

    MediaBlockBinder(BrickBinderContext ctx, View root, Preferences.MediaBrickPrefs p) {
        super(ctx, root);
        this.p = p;
        showSource = find(R.id.brickMediaShowSource);
        titleFirst = find(R.id.brickMediaTitleFirst);
        progressBarEnabled = find(R.id.brickMediaProgressBarEnabled);
        showPlaybackState = find(R.id.brickMediaShowPlaybackState);
        maxWidthSlider = find(R.id.brickMediaMaxWidthSlider);
        lineGapSlider = find(R.id.brickMediaLineGapSlider);
        sourceSection = find(R.id.brickMediaSourceSection);
        sourceFontSizeSlider = find(R.id.brickMediaSourceFontSizeSlider);
        sourceOutlineAlphaSlider = find(R.id.brickMediaSourceOutlineAlphaSlider);
        sourceOutlineWidthSlider = find(R.id.brickMediaSourceOutlineWidthSlider);
        sourceContentAlphaSlider = find(R.id.brickMediaSourceContentAlphaSlider);
        sourceFontFamilyDropdown = find(R.id.brickMediaSourceFontFamilyDropdown);
        sourceFontStyleGroup = find(R.id.brickMediaSourceFontStyleGroup);
        sourceFontBold = find(R.id.brickMediaSourceFontBold);
        sourceFontItalic = find(R.id.brickMediaSourceFontItalic);
        sourceAlignmentDropdown = find(R.id.brickMediaSourceAlignmentDropdown);
        marqueeEnabled = find(R.id.brickMediaMarqueeEnabled);
        titleFontSizeSlider = find(R.id.brickMediaTitleFontSizeSlider);
        titleOutlineAlphaSlider = find(R.id.brickMediaTitleOutlineAlphaSlider);
        titleOutlineWidthSlider = find(R.id.brickMediaTitleOutlineWidthSlider);
        titleContentAlphaSlider = find(R.id.brickMediaTitleContentAlphaSlider);
        titleFontFamilyDropdown = find(R.id.brickMediaTitleFontFamilyDropdown);
        titleFontStyleGroup = find(R.id.brickMediaTitleFontStyleGroup);
        titleFontBold = find(R.id.brickMediaTitleFontBold);
        titleFontItalic = find(R.id.brickMediaTitleFontItalic);
        titleAlignmentDropdown = find(R.id.brickMediaTitleAlignmentDropdown);
        showDuration = find(R.id.brickMediaShowDuration);
        durationSection = find(R.id.brickMediaDurationSection);
        durationFontSizeSlider = find(R.id.brickMediaDurationFontSizeSlider);
        durationContentAlphaSlider = find(R.id.brickMediaDurationContentAlphaSlider);
        durationOutlineAlphaSlider = find(R.id.brickMediaDurationOutlineAlphaSlider);
        durationOutlineWidthSlider = find(R.id.brickMediaDurationOutlineWidthSlider);
        permissionButton = find(R.id.brickMediaPermissionButton);
    }

    @Override
    protected void clearListeners() {
        showSource.setOnCheckedChangeListener(null);
        titleFirst.setOnCheckedChangeListener(null);
        marqueeEnabled.setOnCheckedChangeListener(null);
        progressBarEnabled.setOnCheckedChangeListener(null);
        showPlaybackState.setOnCheckedChangeListener(null);
        showDuration.setOnCheckedChangeListener(null);
        maxWidthSlider.clearOnChangeListeners();
        lineGapSlider.clearOnChangeListeners();
        sourceFontSizeSlider.clearOnChangeListeners();
        sourceOutlineAlphaSlider.clearOnChangeListeners();
        sourceOutlineWidthSlider.clearOnChangeListeners();
        sourceContentAlphaSlider.clearOnChangeListeners();
        sourceFontStyleGroup.clearOnButtonCheckedListeners();
        titleFontSizeSlider.clearOnChangeListeners();
        titleOutlineAlphaSlider.clearOnChangeListeners();
        titleOutlineWidthSlider.clearOnChangeListeners();
        titleContentAlphaSlider.clearOnChangeListeners();
        titleFontStyleGroup.clearOnButtonCheckedListeners();
        durationFontSizeSlider.clearOnChangeListeners();
        durationContentAlphaSlider.clearOnChangeListeners();
        durationOutlineAlphaSlider.clearOnChangeListeners();
        durationOutlineWidthSlider.clearOnChangeListeners();
    }

    @Override
    protected void bindViews() {
        // ============================ Общие ============================
        ctx.bindSwitch(showSource, p.showSource, this::refreshSourceSectionVisibility);
        ctx.bindSwitch(titleFirst, p.titleFirst, null);
        ctx.bindSwitch(progressBarEnabled, p.progressBarEnabled, null);
        ctx.bindSwitch(showPlaybackState, p.showPlaybackState, null);
        refreshSourceSectionVisibility();

        // Upper bound = 80% of the current screen width — gives a useful range on both phones
        // and car head units without locking it to the XML default. Must run BEFORE the pref is
        // seeded, or bindIntSlider clamps the stored value to the XML maximum.
        int screenW = ctx.activity.getResources().getDisplayMetrics().widthPixels;
        float upper = Math.max(maxWidthSlider.getValueFrom() + 1F, screenW * 0.8F);
        maxWidthSlider.setValueTo(upper);
        ctx.bindIntSlider(maxWidthSlider, p.maxWidth, ctx.sizeFormatter());
        ctx.bindIntSlider(lineGapSlider, p.lineGap, ctx.sizeFormatter());

        // ============================ Источник ============================
        ctx.bindIntSlider(sourceFontSizeSlider, p.sourceFontSize, ctx.sizeFormatter());
        ctx.bindIntSlider(sourceOutlineAlphaSlider, p.sourceOutlineAlpha, ctx.plainFormatter());
        ctx.bindIntSlider(sourceOutlineWidthSlider, p.sourceOutlineWidth, ctx.sizeFormatter());
        ctx.bindIntSlider(sourceContentAlphaSlider, p.sourceContentAlpha, ctx.plainFormatter());
        ViewBinder.linkPairDisableOnZero(sourceOutlineAlphaSlider, sourceOutlineWidthSlider);
        ctx.bindFontFamilyDropdown(sourceFontFamilyDropdown, p.sourceFontFamily);
        ctx.bindFontStyleToggles(sourceFontStyleGroup, sourceFontBold, sourceFontItalic,
                p.sourceFontBold, p.sourceFontItalic);
        ctx.bindAlignmentDropdown(sourceAlignmentDropdown, p.sourceAlignment);

        // ============================ Композиция ============================
        // Title uses the inherited TextBrickPrefs fields (fontSize, outline, contentAlpha,
        // fontFamily, fontBold/Italic) so existing presets keep working unchanged.
        ctx.bindSwitch(marqueeEnabled, p.marqueeEnabled, null);
        ctx.bindIntSlider(titleFontSizeSlider, p.fontSize, ctx.sizeFormatter());
        ctx.bindIntSlider(titleOutlineAlphaSlider, p.outlineAlpha, ctx.plainFormatter());
        ctx.bindIntSlider(titleOutlineWidthSlider, p.outlineWidth, ctx.sizeFormatter());
        ctx.bindIntSlider(titleContentAlphaSlider, p.contentAlpha, ctx.plainFormatter());
        ViewBinder.linkPairDisableOnZero(titleOutlineAlphaSlider, titleOutlineWidthSlider);
        ctx.bindFontFamilyDropdown(titleFontFamilyDropdown, p.fontFamily);
        ctx.bindFontStyleToggles(titleFontStyleGroup, titleFontBold, titleFontItalic,
                p.fontBold, p.fontItalic);
        ctx.bindAlignmentDropdown(titleAlignmentDropdown, p.alignment);

        // ============================ Длительность ============================
        ctx.bindSwitch(showDuration, p.showDuration, this::refreshDurationSectionVisibility);
        ctx.bindIntSlider(durationFontSizeSlider, p.durationFontSize, ctx.sizeFormatter());
        ctx.bindIntSlider(durationContentAlphaSlider, p.durationContentAlpha, ctx.plainFormatter());
        ctx.bindIntSlider(durationOutlineAlphaSlider, p.durationOutlineAlpha, ctx.plainFormatter());
        ctx.bindIntSlider(durationOutlineWidthSlider, p.durationOutlineWidth, ctx.sizeFormatter());
        ViewBinder.linkPairDisableOnZero(durationOutlineAlphaSlider, durationOutlineWidthSlider);
        refreshDurationSectionVisibility();

        permissionButton.setOnClickListener(v -> {
            if (!Permissions.isNotificationAccessGranted(ctx.activity)) {
                Toast.makeText(ctx.activity,
                        ctx.activity.getString(R.string.notification_access_required),
                        Toast.LENGTH_SHORT).show();
            }
            try {
                ctx.activity.startActivity(
                        new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Source section is meaningless when the source line isn't rendered. Collapse it whenever the
     * user turns showSource off — this is live state inside the panel, not a per-brick capability,
     * so it cannot come from the control set.
     */
    private void refreshSourceSectionVisibility() {
        sourceSection.setVisibility(p.showSource.get() ? View.VISIBLE : View.GONE);
    }

    private void refreshDurationSectionVisibility() {
        durationSection.setVisibility(p.showDuration.get() ? View.VISIBLE : View.GONE);
    }
}
