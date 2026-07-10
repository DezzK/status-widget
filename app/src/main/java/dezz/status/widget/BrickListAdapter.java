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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BrickListAdapter extends RecyclerView.Adapter<BrickListAdapter.BrickViewHolder> {
    public interface OrderChangedListener {
        void onOrderChanged();
    }

    private final AppCompatActivity activity;
    private final Preferences prefs;
    private final OrderChangedListener orderChangedListener;
    private final List<BrickType> bricks;
    private ItemTouchHelper itemTouchHelper;
    /** Currently expanded brick — only one panel is open at a time. {@code null} = all collapsed. */
    @androidx.annotation.Nullable
    private BrickType expandedType;

    public BrickListAdapter(AppCompatActivity activity, Preferences prefs,
                            OrderChangedListener orderChangedListener) {
        this.activity = activity;
        this.prefs = prefs;
        this.orderChangedListener = orderChangedListener;
        this.bricks = new ArrayList<>(BrickType.parseOrder(prefs.brickOrder.get()));
        setHasStableIds(true);
    }

    public void attachItemTouchHelper(ItemTouchHelper helper) {
        this.itemTouchHelper = helper;
    }

    public List<BrickType> getBricks() {
        return bricks;
    }

    public void setBricks(List<BrickType> newOrder) {
        bricks.clear();
        bricks.addAll(newOrder);
        notifyDataSetChanged();
        persist();
    }

    public void addBrick(BrickType type) {
        if (bricks.contains(type)) return;
        bricks.add(type);
        notifyItemInserted(bricks.size() - 1);
        persist();
    }

    public void removeBrick(BrickType type) {
        int idx = bricks.indexOf(type);
        if (idx < 0) return;
        bricks.remove(idx);
        notifyItemRemoved(idx);
        persist();
    }

    public void moveBrick(int fromPos, int toPos) {
        Collections.swap(bricks, fromPos, toPos);
        notifyItemMoved(fromPos, toPos);
        persist();
    }

    private void persist() {
        prefs.brickOrder.set(BrickType.serializeOrder(bricks));
        notifyService();
        if (orderChangedListener != null) {
            orderChangedListener.onOrderChanged();
        }
    }

    private void toggleExpanded(BrickType type) {
        if (type == expandedType) {
            expandedType = null;
        } else {
            expandedType = type;
        }
        notifyDataSetChanged();
    }

    private void notifyService() {
        if (WidgetService.isRunning()) {
            WidgetService.getInstance().applyPreferences();
        }
    }

    private int brickTitleRes(BrickType type) {
        switch (type) {
            case TIME:
                return R.string.brick_title_time;
            case DATE:
                return R.string.brick_title_date;
            case MEDIA:
                return R.string.brick_title_media;
            case WIFI:
                return R.string.brick_title_wifi;
            case GPS:
                return R.string.brick_title_gps;
            case BLUETOOTH:
                return R.string.brick_title_bluetooth;
            case INDOOR_TEMP:
                return R.string.brick_title_indoor_temp;
            case OUTDOOR_TEMP:
                return R.string.brick_title_outdoor_temp;
            default:
                return 0;
        }
    }

    private String hideTitleFor(BrickType type) {
        return activity.getString(R.string.brick_hide_in_apps_title,
                activity.getString(brickTitleRes(type)));
    }

    /** True if any other brick currently inherits its hide list from {@code type}. */
    private boolean brickHasChildren(BrickType type) {
        for (BrickType other : BrickType.values()) {
            if (other == type) continue;
            if (type.name().equals(prefs.hideSourceFor(other).get())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return bricks.size();
    }

    @Override
    public long getItemId(int position) {
        return bricks.get(position).ordinal();
    }

    @NonNull
    @Override
    public BrickViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.brick_item, parent, false);
        return new BrickViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BrickViewHolder holder, int position) {
        holder.bind(bricks.get(position));
    }

    class BrickViewHolder extends RecyclerView.ViewHolder {
        final TextView brickTitle;
        final ImageView brickDragHandle;
        final ImageView brickExpand;
        final LinearLayout brickHeader;
        final LinearLayout brickPanel;
        final TextView brickSizeLabel;
        final Slider brickSizeSlider;
        final Slider brickOutlineAlphaSlider;
        final Slider brickOutlineWidthSlider;
        final Slider brickContentAlphaSlider;
        final Slider brickMarginStartSlider;
        final Slider brickMarginEndSlider;
        final com.google.android.material.textfield.TextInputLayout brickStatusAlignmentLayout;
        final MaterialAutoCompleteTextView brickStatusAlignmentDropdown;
        final Slider brickAdjustYSlider;
        final LinearLayout brickDateBlock;
        final MaterialSwitch brickDateShowDate;
        final MaterialSwitch brickDateShowDayOfWeek;
        final MaterialSwitch brickDateShowFullName;
        final MaterialSwitch brickDateBeforeDayOfWeek;
        final MaterialSwitch brickDateOneLineLayout;
        final com.google.android.material.textfield.TextInputLayout brickDateStatusAlignmentLayout;
        final MaterialAutoCompleteTextView brickDateStatusAlignmentDropdown;
        final MaterialAutoCompleteTextView brickDateAlignmentDropdown;
        final LinearLayout brickGpsBlock;
        final MaterialSwitch brickGpsShowSatelliteBadge;
        final LinearLayout brickBluetoothBlock;
        final MaterialSwitch brickBluetoothShowDeviceCountBadge;
        // Containers in the generic brick area we GONE for media — media duplicates everything
        // text-related inside its own sectioned block.
        final LinearLayout brickSizeAdjustRow;
        final LinearLayout brickOutlineRow;
        final LinearLayout brickContentAlphaRow;
        final LinearLayout brickMarginRow;

        final LinearLayout brickMediaBlock;
        // Общие
        final MaterialSwitch brickMediaShowSource;
        final MaterialSwitch brickMediaTitleFirst;
        final MaterialSwitch brickMediaMarqueeEnabled;
        final MaterialSwitch brickMediaProgressBarEnabled;
        final Slider brickMediaMaxWidthSlider;
        final Slider brickMediaMarginStartSlider;
        final Slider brickMediaMarginEndSlider;
        final Slider brickMediaAdjustYSlider;
        final com.google.android.material.textfield.TextInputLayout brickMediaStatusAlignmentLayout;
        final MaterialAutoCompleteTextView brickMediaStatusAlignmentDropdown;
        // Общие (продолжение)
        final Slider brickMediaLineGapSlider;
        // Источник
        final LinearLayout brickMediaSourceSection;
        final Slider brickMediaSourceFontSizeSlider;
        final Slider brickMediaSourceOutlineAlphaSlider;
        final Slider brickMediaSourceOutlineWidthSlider;
        final Slider brickMediaSourceContentAlphaSlider;
        final MaterialAutoCompleteTextView brickMediaSourceFontFamilyDropdown;
        final MaterialButtonToggleGroup brickMediaSourceFontStyleGroup;
        final MaterialButton brickMediaSourceFontBold;
        final MaterialButton brickMediaSourceFontItalic;
        final MaterialAutoCompleteTextView brickMediaSourceAlignmentDropdown;
        // Композиция
        final Slider brickMediaTitleFontSizeSlider;
        final Slider brickMediaTitleOutlineAlphaSlider;
        final Slider brickMediaTitleOutlineWidthSlider;
        final Slider brickMediaTitleContentAlphaSlider;
        final MaterialAutoCompleteTextView brickMediaTitleFontFamilyDropdown;
        final MaterialButtonToggleGroup brickMediaTitleFontStyleGroup;
        final MaterialButton brickMediaTitleFontBold;
        final MaterialButton brickMediaTitleFontItalic;
        final MaterialAutoCompleteTextView brickMediaTitleAlignmentDropdown;
        // Длительность
        final MaterialSwitch brickMediaShowDuration;
        final LinearLayout brickMediaDurationSection;
        final Slider brickMediaDurationFontSizeSlider;
        final Slider brickMediaDurationContentAlphaSlider;
        final Slider brickMediaDurationOutlineAlphaSlider;
        final Slider brickMediaDurationOutlineWidthSlider;

        final MaterialButton brickMediaPermissionButton;
        final LinearLayout brickFontBlock;
        final TextView brickFontBlockHeader;
        final MaterialAutoCompleteTextView brickFontFamilyDropdown;
        final MaterialButtonToggleGroup brickFontStyleGroup;
        final MaterialButton brickFontBold;
        final MaterialButton brickFontItalic;
        final LinearLayout brickHideOwnBlock;
        final MaterialButton brickHideInAppsButton;
        final TextView brickHideApplyToLabel;
        final ChipGroup brickHideApplyToChips;
        final LinearLayout brickHideInheritedBlock;
        final TextView brickHideInheritedHint;
        final MaterialButton brickHideUseOwnButton;
        /**
         * One switch lives inside each hide block so it sits visually next to the relevant
         * button. Both are bound to the same {@code hideKeepsSpace} pref; only the one whose
         * parent block is visible is interactable at any given time.
         */
        final MaterialSwitch brickHideKeepsSpaceOwnSwitch;
        final MaterialSwitch brickHideKeepsSpaceInheritedSwitch;
        final MaterialButton brickResetButton;
        final MaterialButton brickRemoveButton;

        BrickViewHolder(@NonNull View itemView) {
            super(itemView);
            brickTitle = itemView.findViewById(R.id.brickTitle);
            brickDragHandle = itemView.findViewById(R.id.brickDragHandle);
            brickExpand = itemView.findViewById(R.id.brickExpand);
            brickHeader = itemView.findViewById(R.id.brickHeader);
            brickPanel = itemView.findViewById(R.id.brickPanel);
            brickSizeLabel = itemView.findViewById(R.id.brickSizeLabel);
            brickSizeSlider = itemView.findViewById(R.id.brickSizeSlider);
            brickOutlineAlphaSlider = itemView.findViewById(R.id.brickOutlineAlphaSlider);
            brickOutlineWidthSlider = itemView.findViewById(R.id.brickOutlineWidthSlider);
            brickContentAlphaSlider = itemView.findViewById(R.id.brickContentAlphaSlider);
            brickMarginStartSlider = itemView.findViewById(R.id.brickMarginStartSlider);
            brickMarginEndSlider = itemView.findViewById(R.id.brickMarginEndSlider);
            brickStatusAlignmentLayout = itemView.findViewById(R.id.brickStatusAlignmentLayout);
            brickStatusAlignmentDropdown = itemView.findViewById(R.id.brickStatusAlignmentDropdown);
            brickAdjustYSlider = itemView.findViewById(R.id.brickAdjustYSlider);
            brickDateBlock = itemView.findViewById(R.id.brickDateBlock);
            brickDateShowDate = itemView.findViewById(R.id.brickDateShowDate);
            brickDateShowDayOfWeek = itemView.findViewById(R.id.brickDateShowDayOfWeek);
            brickDateShowFullName = itemView.findViewById(R.id.brickDateShowFullName);
            brickDateBeforeDayOfWeek = itemView.findViewById(R.id.brickDateBeforeDayOfWeek);
            brickDateOneLineLayout = itemView.findViewById(R.id.brickDateOneLineLayout);
            brickDateStatusAlignmentLayout = itemView.findViewById(R.id.brickDateStatusAlignmentLayout);
            brickDateStatusAlignmentDropdown = itemView.findViewById(R.id.brickDateStatusAlignmentDropdown);
            brickDateAlignmentDropdown = itemView.findViewById(R.id.brickDateAlignmentDropdown);
            brickGpsBlock = itemView.findViewById(R.id.brickGpsBlock);
            brickGpsShowSatelliteBadge = itemView.findViewById(R.id.brickGpsShowSatelliteBadge);
            brickBluetoothBlock = itemView.findViewById(R.id.brickBluetoothBlock);
            brickBluetoothShowDeviceCountBadge = itemView.findViewById(R.id.brickBluetoothShowDeviceCountBadge);
            brickSizeAdjustRow = itemView.findViewById(R.id.brickSizeAdjustRow);
            brickOutlineRow = itemView.findViewById(R.id.brickOutlineRow);
            brickContentAlphaRow = itemView.findViewById(R.id.brickContentAlphaRow);
            brickMarginRow = itemView.findViewById(R.id.brickMarginRow);
            brickMediaBlock = itemView.findViewById(R.id.brickMediaBlock);
            brickMediaShowSource = itemView.findViewById(R.id.brickMediaShowSource);
            brickMediaTitleFirst = itemView.findViewById(R.id.brickMediaTitleFirst);
            brickMediaMarqueeEnabled = itemView.findViewById(R.id.brickMediaMarqueeEnabled);
            brickMediaProgressBarEnabled = itemView.findViewById(R.id.brickMediaProgressBarEnabled);
            brickMediaMaxWidthSlider = itemView.findViewById(R.id.brickMediaMaxWidthSlider);
            brickMediaMarginStartSlider = itemView.findViewById(R.id.brickMediaMarginStartSlider);
            brickMediaMarginEndSlider = itemView.findViewById(R.id.brickMediaMarginEndSlider);
            brickMediaAdjustYSlider = itemView.findViewById(R.id.brickMediaAdjustYSlider);
            brickMediaStatusAlignmentLayout = itemView.findViewById(R.id.brickMediaStatusAlignmentLayout);
            brickMediaStatusAlignmentDropdown = itemView.findViewById(R.id.brickMediaStatusAlignmentDropdown);
            brickMediaLineGapSlider = itemView.findViewById(R.id.brickMediaLineGapSlider);
            brickMediaSourceSection = itemView.findViewById(R.id.brickMediaSourceSection);
            brickMediaSourceFontSizeSlider = itemView.findViewById(R.id.brickMediaSourceFontSizeSlider);
            brickMediaSourceOutlineAlphaSlider = itemView.findViewById(R.id.brickMediaSourceOutlineAlphaSlider);
            brickMediaSourceOutlineWidthSlider = itemView.findViewById(R.id.brickMediaSourceOutlineWidthSlider);
            brickMediaSourceContentAlphaSlider = itemView.findViewById(R.id.brickMediaSourceContentAlphaSlider);
            brickMediaSourceFontFamilyDropdown = itemView.findViewById(R.id.brickMediaSourceFontFamilyDropdown);
            brickMediaSourceFontStyleGroup = itemView.findViewById(R.id.brickMediaSourceFontStyleGroup);
            brickMediaSourceFontBold = itemView.findViewById(R.id.brickMediaSourceFontBold);
            brickMediaSourceFontItalic = itemView.findViewById(R.id.brickMediaSourceFontItalic);
            brickMediaSourceAlignmentDropdown = itemView.findViewById(R.id.brickMediaSourceAlignmentDropdown);
            brickMediaTitleFontSizeSlider = itemView.findViewById(R.id.brickMediaTitleFontSizeSlider);
            brickMediaTitleOutlineAlphaSlider = itemView.findViewById(R.id.brickMediaTitleOutlineAlphaSlider);
            brickMediaTitleOutlineWidthSlider = itemView.findViewById(R.id.brickMediaTitleOutlineWidthSlider);
            brickMediaTitleContentAlphaSlider = itemView.findViewById(R.id.brickMediaTitleContentAlphaSlider);
            brickMediaTitleFontFamilyDropdown = itemView.findViewById(R.id.brickMediaTitleFontFamilyDropdown);
            brickMediaTitleFontStyleGroup = itemView.findViewById(R.id.brickMediaTitleFontStyleGroup);
            brickMediaTitleFontBold = itemView.findViewById(R.id.brickMediaTitleFontBold);
            brickMediaTitleFontItalic = itemView.findViewById(R.id.brickMediaTitleFontItalic);
            brickMediaTitleAlignmentDropdown = itemView.findViewById(R.id.brickMediaTitleAlignmentDropdown);
            brickMediaShowDuration = itemView.findViewById(R.id.brickMediaShowDuration);
            brickMediaDurationSection = itemView.findViewById(R.id.brickMediaDurationSection);
            brickMediaDurationFontSizeSlider = itemView.findViewById(R.id.brickMediaDurationFontSizeSlider);
            brickMediaDurationContentAlphaSlider = itemView.findViewById(R.id.brickMediaDurationContentAlphaSlider);
            brickMediaDurationOutlineAlphaSlider = itemView.findViewById(R.id.brickMediaDurationOutlineAlphaSlider);
            brickMediaDurationOutlineWidthSlider = itemView.findViewById(R.id.brickMediaDurationOutlineWidthSlider);
            brickMediaPermissionButton = itemView.findViewById(R.id.brickMediaPermissionButton);
            brickFontBlock = itemView.findViewById(R.id.brickFontBlock);
            brickFontBlockHeader = itemView.findViewById(R.id.brickFontBlockHeader);
            brickFontFamilyDropdown = itemView.findViewById(R.id.brickFontFamilyDropdown);
            brickFontStyleGroup = itemView.findViewById(R.id.brickFontStyleGroup);
            brickFontBold = itemView.findViewById(R.id.brickFontBold);
            brickFontItalic = itemView.findViewById(R.id.brickFontItalic);
            brickHideOwnBlock = itemView.findViewById(R.id.brickHideOwnBlock);
            brickHideInAppsButton = itemView.findViewById(R.id.brickHideInAppsButton);
            brickHideApplyToLabel = itemView.findViewById(R.id.brickHideApplyToLabel);
            brickHideApplyToChips = itemView.findViewById(R.id.brickHideApplyToChips);
            brickHideInheritedBlock = itemView.findViewById(R.id.brickHideInheritedBlock);
            brickHideInheritedHint = itemView.findViewById(R.id.brickHideInheritedHint);
            brickHideUseOwnButton = itemView.findViewById(R.id.brickHideUseOwnButton);
            brickHideKeepsSpaceOwnSwitch = itemView.findViewById(R.id.brickHideKeepsSpaceOwnSwitch);
            brickHideKeepsSpaceInheritedSwitch = itemView.findViewById(R.id.brickHideKeepsSpaceInheritedSwitch);
            brickResetButton = itemView.findViewById(R.id.brickResetButton);
            brickRemoveButton = itemView.findViewById(R.id.brickRemoveButton);
        }

        @SuppressLint("ClickableViewAccessibility")
        void bind(BrickType type) {
            brickTitle.setText(titleFor(type));
            brickHeader.setOnClickListener(v -> toggleExpanded(type));
            brickExpand.setOnClickListener(v -> toggleExpanded(type));
            brickDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                    itemTouchHelper.startDrag(this);
                }
                return false;
            });
            brickRemoveButton.setOnClickListener(v -> removeBrick(type));
            brickResetButton.setOnClickListener(v -> confirmResetBrick(type));

            // Reset listeners before rebinding (recycled holders).
            brickSizeSlider.clearOnChangeListeners();
            brickOutlineAlphaSlider.clearOnChangeListeners();
            brickOutlineWidthSlider.clearOnChangeListeners();
            brickContentAlphaSlider.clearOnChangeListeners();
            brickMarginStartSlider.clearOnChangeListeners();
            brickMarginEndSlider.clearOnChangeListeners();
            brickAdjustYSlider.clearOnChangeListeners();
            brickDateShowDate.setOnCheckedChangeListener(null);
            brickDateShowDayOfWeek.setOnCheckedChangeListener(null);
            brickDateShowFullName.setOnCheckedChangeListener(null);
            brickDateBeforeDayOfWeek.setOnCheckedChangeListener(null);
            brickDateOneLineLayout.setOnCheckedChangeListener(null);
            brickGpsShowSatelliteBadge.setOnCheckedChangeListener(null);
            brickBluetoothShowDeviceCountBadge.setOnCheckedChangeListener(null);
            brickMediaShowSource.setOnCheckedChangeListener(null);
            brickMediaTitleFirst.setOnCheckedChangeListener(null);
            brickMediaMarqueeEnabled.setOnCheckedChangeListener(null);
            brickMediaProgressBarEnabled.setOnCheckedChangeListener(null);
            brickMediaShowDuration.setOnCheckedChangeListener(null);
            brickMediaDurationFontSizeSlider.clearOnChangeListeners();
            brickMediaDurationContentAlphaSlider.clearOnChangeListeners();
            brickMediaDurationOutlineAlphaSlider.clearOnChangeListeners();
            brickMediaDurationOutlineWidthSlider.clearOnChangeListeners();
            brickMediaMaxWidthSlider.clearOnChangeListeners();
            brickMediaMarginStartSlider.clearOnChangeListeners();
            brickMediaMarginEndSlider.clearOnChangeListeners();
            brickMediaAdjustYSlider.clearOnChangeListeners();
            brickMediaLineGapSlider.clearOnChangeListeners();
            brickMediaSourceFontSizeSlider.clearOnChangeListeners();
            brickMediaSourceOutlineAlphaSlider.clearOnChangeListeners();
            brickMediaSourceOutlineWidthSlider.clearOnChangeListeners();
            brickMediaSourceContentAlphaSlider.clearOnChangeListeners();
            brickMediaSourceFontStyleGroup.clearOnButtonCheckedListeners();
            brickMediaTitleFontSizeSlider.clearOnChangeListeners();
            brickMediaTitleOutlineAlphaSlider.clearOnChangeListeners();
            brickMediaTitleOutlineWidthSlider.clearOnChangeListeners();
            brickMediaTitleContentAlphaSlider.clearOnChangeListeners();
            brickMediaTitleFontStyleGroup.clearOnButtonCheckedListeners();
            brickHideKeepsSpaceOwnSwitch.setOnCheckedChangeListener(null);
            brickHideKeepsSpaceInheritedSwitch.setOnCheckedChangeListener(null);
            brickFontStyleGroup.clearOnButtonCheckedListeners();
            // Reset visibility of the generic text rows. bindMediaBrick collapses them so the
            // media brick only shows controls organised into its own three sections; other text
            // bricks (Time/Date) use the generic area and need them VISIBLE.
            brickSizeAdjustRow.setVisibility(View.VISIBLE);
            brickOutlineRow.setVisibility(View.VISIBLE);
            brickContentAlphaRow.setVisibility(View.VISIBLE);
            brickMarginRow.setVisibility(View.VISIBLE);
            brickFontBlockHeader.setVisibility(View.GONE);

            switch (type) {
                case TIME:
                    bindTextBrick(prefs.time);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
                case DATE:
                    bindTextBrick(prefs.date);
                    showDateBlock(true);
                    bindDateBlock();
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
                case MEDIA:
                    bindMediaBrick(prefs.media);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(true);
                    break;
                case WIFI:
                    bindIconBrick(prefs.wifi);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
                case GPS:
                    bindIconBrick(prefs.gps);
                    showDateBlock(false);
                    showGpsBlock(true);
                    bindGpsBlock();
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
                case BLUETOOTH:
                    bindIconBrick(prefs.bluetooth);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(true);
                    bindBluetoothBlock();
                    showMediaBlock(false);
                    break;
                case INDOOR_TEMP:
                    bindTextBrick(prefs.indoorTemp);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
                case OUTDOOR_TEMP:
                    bindTextBrick(prefs.outdoorTemp);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showBluetoothBlock(false);
                    showMediaBlock(false);
                    break;
            }

            bindHideBlock(type);
            bindStatusAlignment(type);
            applyExpandState(type);
        }

        /**
         * In-block status alignment dropdown for DATE / MEDIA — paired with the text alignment
         * dropdown in a two-column row. Hidden in floating mode, in which case the sibling text
         * alignment dropdown stretches to the full row width (its layout_weight=1 wins).
         */
        private void bindInBlockStatusAlignment(BrickType type,
                                                com.google.android.material.textfield.TextInputLayout layout,
                                                MaterialAutoCompleteTextView dropdown) {
            boolean statusBar = prefs.widgetMode.get() == 1;
            layout.setVisibility(statusBar ? View.VISIBLE : View.GONE);
            if (!statusBar) return;
            String[] items = activity.getResources().getStringArray(R.array.brick_status_alignments);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    items);
            dropdown.setAdapter(adapter);
            int current = Math.max(0, Math.min(prefs.statusAlignmentFor(type).get(), items.length - 1));
            dropdown.setText(items[current], false);
            dropdown.setOnItemClickListener((parent, view, position, id) -> {
                prefs.statusAlignmentFor(type).set(position);
                notifyService();
            });
        }

        private void bindStatusAlignment(BrickType type) {
            // DATE and MEDIA render their status alignment inside the per-type block (paired with the
            // text alignment), so the shared dropdown must stay hidden for them regardless of mode.
            if (type == BrickType.DATE || type == BrickType.MEDIA) {
                brickStatusAlignmentLayout.setVisibility(View.GONE);
                return;
            }
            // Only relevant in status-bar mode — hide the dropdown otherwise.
            boolean statusBar = prefs.widgetMode.get() == 1;
            brickStatusAlignmentLayout.setVisibility(statusBar ? View.VISIBLE : View.GONE);
            if (!statusBar) return;

            String[] items = activity.getResources().getStringArray(R.array.brick_status_alignments);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    items);
            brickStatusAlignmentDropdown.setAdapter(adapter);
            int current = Math.max(0, Math.min(prefs.statusAlignmentFor(type).get(), items.length - 1));
            brickStatusAlignmentDropdown.setText(items[current], false);
            brickStatusAlignmentDropdown.setOnItemClickListener((parent, view, position, id) -> {
                prefs.statusAlignmentFor(type).set(position);
                notifyService();
            });
        }

        private void bindHideBlock(BrickType type) {
            String src = prefs.hideSourceFor(type).get();
            BrickType parent = BrickType.fromName(src);
            if (parent != null && parent != type) {
                // This brick inherits from another brick.
                brickHideOwnBlock.setVisibility(View.GONE);
                brickHideInheritedBlock.setVisibility(View.VISIBLE);
                brickHideInheritedHint.setText(activity.getString(
                        R.string.brick_hide_inherited_hint, brickTitleString(parent)));
                brickHideUseOwnButton.setOnClickListener(v -> {
                    prefs.hideSourceFor(type).set("");
                    notifyService();
                    notifyDataSetChanged();
                });
            } else {
                // This brick has its own list (and may have children inheriting from it).
                brickHideInheritedBlock.setVisibility(View.GONE);
                brickHideOwnBlock.setVisibility(View.VISIBLE);

                int count = prefs.hideListFor(type).get().size();
                brickHideInAppsButton.setText(count > 0
                        ? activity.getString(R.string.brick_hide_in_apps_count, count)
                        : activity.getString(R.string.brick_hide_in_apps));
                brickHideInAppsButton.setOnClickListener(v -> openHideInApps(type));

                bindApplyToChips(type);
            }

            // The INVISIBLE/GONE toggle is per-brick and applies in both own/inherited modes.
            // We keep one switch in each block so it sits next to the corresponding
            // hide-in-apps / use-own-list button — only the one inside the currently visible
            // block is interactable, the sibling is hidden by its container's GONE.
            boolean keepsSpace = prefs.hideKeepsSpaceFor(type).get();
            CompoundButton.OnCheckedChangeListener keepsSpaceListener = (v, c) -> {
                prefs.hideKeepsSpaceFor(type).set(c);
                notifyService();
            };
            brickHideKeepsSpaceOwnSwitch.setChecked(keepsSpace);
            brickHideKeepsSpaceOwnSwitch.setOnCheckedChangeListener(keepsSpaceListener);
            brickHideKeepsSpaceInheritedSwitch.setChecked(keepsSpace);
            brickHideKeepsSpaceInheritedSwitch.setOnCheckedChangeListener(keepsSpaceListener);
        }

        private void bindApplyToChips(BrickType ownerType) {
            brickHideApplyToChips.removeAllViews();
            int otherCount = 0;
            for (BrickType candidate : BrickType.values()) {
                if (candidate == ownerType) continue;
                otherCount++;
                Chip chip = new Chip(activity);
                chip.setText(brickTitleString(candidate));
                chip.setCheckable(true);
                String candidateSource = prefs.hideSourceFor(candidate).get();
                boolean inheritsFromUs = ownerType.name().equals(candidateSource);
                chip.setChecked(inheritsFromUs);
                // A brick that already shares its list with a different parent is OK to retarget;
                // but a brick that itself has children would create a 2-level chain — disallow.
                boolean candidateHasChildren = brickHasChildren(candidate);
                boolean enabled = inheritsFromUs || !candidateHasChildren;
                chip.setEnabled(enabled);
                chip.setOnClickListener(v -> {
                    if (chip.isChecked()) {
                        prefs.hideSourceFor(candidate).set(ownerType.name());
                    } else {
                        prefs.hideSourceFor(candidate).set("");
                    }
                    notifyService();
                    notifyDataSetChanged();
                });
                brickHideApplyToChips.addView(chip);
            }
            boolean visible = otherCount > 0;
            brickHideApplyToLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
            brickHideApplyToChips.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        private void confirmResetBrick(BrickType type) {
            new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.brick_reset_title)
                    .setMessage(R.string.brick_reset_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.brick_reset_button, (d, w) -> {
                        prefs.resetBrick(type);
                        notifyService();
                        notifyDataSetChanged();
                    })
                    .show();
        }

        private void openHideInApps(BrickType type) {
            try {
                if (!Permissions.isUsageAccessGranted(activity)) {
                    Toast.makeText(activity, R.string.usage_access_required, Toast.LENGTH_LONG).show();
                    openUsageAccessSettings();
                    return;
                }
                Intent intent = new Intent(activity, AppSelectionActivity.class);
                intent.putExtra(AppSelectionActivity.EXTRA_PREF_KEY, prefs.hideListKeyFor(type));
                intent.putExtra(AppSelectionActivity.EXTRA_TITLE, hideTitleFor(type));
                activity.startActivity(intent);
            } catch (Throwable t) {
                // Previously this was a silent catch — but several users reported the app crashing
                // here on certain OEM ROMs. Surface the error in a toast so they can report it.
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : "");
                Toast.makeText(activity,
                        activity.getString(R.string.app_selection_load_failed_message, msg),
                        Toast.LENGTH_LONG).show();
            }
        }

        private void openUsageAccessSettings() {
            if (!SettingsLauncher.openUsageAccessSettings(activity)) {
                Toast.makeText(activity, R.string.system_settings_not_available, Toast.LENGTH_LONG).show();
            }
        }

        private String brickTitleString(BrickType type) {
            return activity.getString(brickTitleRes(type));
        }

        private CharSequence titleFor(BrickType type) {
            switch (type) {
                case TIME:
                    return activity.getString(R.string.brick_title_time);
                case DATE:
                    return activity.getString(R.string.brick_title_date);
                case MEDIA:
                    return activity.getString(R.string.brick_title_media);
                case WIFI:
                    return activity.getString(R.string.brick_title_wifi);
                case GPS:
                    return activity.getString(R.string.brick_title_gps);
                case BLUETOOTH:
                    return activity.getString(R.string.brick_title_bluetooth);
                case INDOOR_TEMP:
                    return activity.getString(R.string.brick_title_indoor_temp);
                case OUTDOOR_TEMP:
                    return activity.getString(R.string.brick_title_outdoor_temp);
                default:
                    return "";
            }
        }

        private void applyExpandState(BrickType type) {
            boolean expanded = (type == expandedType);
            brickPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            brickExpand.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        }

        private void bindTextBrick(Preferences.TextBrickPrefs p) {
            brickSizeLabel.setText(R.string.brick_font_size);
            brickSizeSlider.setContentDescription(activity.getString(R.string.brick_font_size));
            brickSizeSlider.setValueFrom(10);
            brickSizeSlider.setValueTo(500);
            bindIntSlider(brickSizeSlider, p.fontSize, sizeFormatter());
            bindIntSlider(brickOutlineAlphaSlider, p.outlineAlpha, plainFormatter());
            bindIntSlider(brickOutlineWidthSlider, p.outlineWidth, sizeFormatter());
            bindIntSlider(brickMarginStartSlider, p.marginStart, sizeFormatter());
            bindIntSlider(brickMarginEndSlider, p.marginEnd, sizeFormatter());
            bindIntSlider(brickAdjustYSlider, p.adjustY, offsetFormatter());
            bindIntSlider(brickContentAlphaSlider, p.contentAlpha, plainFormatter());
            ViewBinder.linkPairDisableOnZero(brickOutlineAlphaSlider, brickOutlineWidthSlider);
            bindFontBlock(p);
        }

        private void bindIconBrick(Preferences.IconBrickPrefs p) {
            brickSizeLabel.setText(R.string.brick_size);
            brickSizeSlider.setContentDescription(activity.getString(R.string.brick_size));
            brickSizeSlider.setValueFrom(10);
            brickSizeSlider.setValueTo(600);
            bindIntSlider(brickSizeSlider, p.size, sizeFormatter());
            bindIntSlider(brickOutlineAlphaSlider, p.outlineAlpha, plainFormatter());
            bindIntSlider(brickOutlineWidthSlider, p.outlineWidth, sizeFormatter());
            bindIntSlider(brickMarginStartSlider, p.marginStart, sizeFormatter());
            bindIntSlider(brickMarginEndSlider, p.marginEnd, sizeFormatter());
            bindIntSlider(brickAdjustYSlider, p.adjustY, offsetFormatter());
            bindIntSlider(brickContentAlphaSlider, p.contentAlpha, plainFormatter());
            ViewBinder.linkPairDisableOnZero(brickOutlineAlphaSlider, brickOutlineWidthSlider);
            brickFontBlock.setVisibility(View.GONE);
        }

        /**
         * Media has a sectioned layout of its own (Общие / Источник / Композиция) and source
         * vs title get independent text params, so we collapse the generic text rows from the
         * brick area instead of duplicating them. Everything text-related is bound inside
         * {@link #bindMediaBlock()}.
         */
        private void bindMediaBrick(Preferences.MediaBrickPrefs p) {
            brickSizeAdjustRow.setVisibility(View.GONE);
            brickOutlineRow.setVisibility(View.GONE);
            brickContentAlphaRow.setVisibility(View.GONE);
            brickMarginRow.setVisibility(View.GONE);
            brickFontBlock.setVisibility(View.GONE);
            brickFontBlockHeader.setVisibility(View.GONE);
            bindMediaBlock();
        }

        private void bindFontBlock(Preferences.TextBrickPrefs p) {
            brickFontBlock.setVisibility(View.VISIBLE);
            bindFontFamilyDropdown(brickFontFamilyDropdown, p.fontFamily);
            bindFontStyleToggles(brickFontStyleGroup,
                    brickFontBold, brickFontItalic,
                    p.fontBold, p.fontItalic);
        }

        /**
         * Wire a font-family dropdown to a {@link Preferences.Str} pref carrying {@link Fonts}
         * keys. Reused for the main brick font block and the media source line, which both
         * need the same UX but bind different prefs.
         */
        private void bindFontFamilyDropdown(MaterialAutoCompleteTextView dropdown,
                                            Preferences.Str familyPref) {
            String[] labels = new String[Fonts.ALL.size()];
            for (int i = 0; i < Fonts.ALL.size(); i++) {
                labels[i] = activity.getString(Fonts.ALL.get(i).labelRes);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    labels);
            dropdown.setAdapter(adapter);
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

        private void bindFontStyleToggles(MaterialButtonToggleGroup group,
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

        private void setAndNotify(Preferences.Bool pref, boolean v) {
            pref.set(v);
            notifyService();
        }

        /** Source section is meaningless when the source line isn't rendered. Collapse it
         *  whenever the user turns showSource off. */
        private void refreshMediaSourceSectionVisibility() {
            brickMediaSourceSection.setVisibility(
                    prefs.media.showSource.get() ? View.VISIBLE : View.GONE);
        }

        private void refreshMediaDurationSectionVisibility() {
            brickMediaDurationSection.setVisibility(
                    prefs.media.showDuration.get() ? View.VISIBLE : View.GONE);
        }

        /** Reusable Start/Center/End alignment dropdown bound to an int 0..2 preference. */
        private void bindAlignmentDropdown(MaterialAutoCompleteTextView dropdown,
                                           Preferences.Int pref) {
            String[] options = activity.getResources()
                    .getStringArray(R.array.calendar_alignment_types);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    options);
            dropdown.setAdapter(adapter);
            int current = clamp(pref.get(), 0, options.length - 1);
            dropdown.setText(options[current], false);
            dropdown.setOnItemClickListener((parent, view, position, id) -> {
                pref.set(position);
                notifyService();
            });
        }

        private void setAndNotify(Preferences.Str pref, String v) {
            pref.set(v);
            notifyService();
        }

        private void bindDateBlock() {
            // "Date before day of week" and "one-line layout" both describe how the two
            // sub-fields relate to each other — meaningless if only one (or zero) of them is
            // shown. Recompute enabled state whenever either visibility switch changes.
            Runnable refreshDatePairControls = () -> {
                boolean bothShown = brickDateShowDate.isChecked()
                        && brickDateShowDayOfWeek.isChecked();
                brickDateBeforeDayOfWeek.setEnabled(bothShown);
                brickDateOneLineLayout.setEnabled(bothShown);
            };

            brickDateShowDate.setChecked(prefs.date.showDate.get());
            brickDateShowDate.setOnCheckedChangeListener((v, c) -> {
                prefs.date.showDate.set(c);
                refreshDatePairControls.run();
                notifyService();
            });
            brickDateShowDayOfWeek.setChecked(prefs.date.showDayOfWeek.get());
            brickDateShowDayOfWeek.setOnCheckedChangeListener((v, c) -> {
                prefs.date.showDayOfWeek.set(c);
                refreshDatePairControls.run();
                notifyService();
            });
            brickDateShowFullName.setChecked(prefs.date.showFullName.get());
            brickDateShowFullName.setOnCheckedChangeListener((v, c) -> {
                prefs.date.showFullName.set(c);
                notifyService();
            });
            brickDateBeforeDayOfWeek.setChecked(prefs.date.dateBeforeDayOfWeek.get());
            brickDateBeforeDayOfWeek.setOnCheckedChangeListener((v, c) -> {
                prefs.date.dateBeforeDayOfWeek.set(c);
                notifyService();
            });
            brickDateOneLineLayout.setChecked(prefs.date.oneLineLayout.get());
            brickDateOneLineLayout.setOnCheckedChangeListener((v, c) -> {
                prefs.date.oneLineLayout.set(c);
                notifyService();
            });
            refreshDatePairControls.run();

            String[] alignments = activity.getResources().getStringArray(R.array.calendar_alignment_types);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    alignments);
            brickDateAlignmentDropdown.setAdapter(adapter);
            int currentAlignment = clamp(prefs.date.alignment.get(), 0, alignments.length - 1);
            brickDateAlignmentDropdown.setText(alignments[currentAlignment], false);
            brickDateAlignmentDropdown.setOnItemClickListener((parent, view, position, id) -> {
                prefs.date.alignment.set(position);
                notifyService();
            });

            bindInBlockStatusAlignment(BrickType.DATE,
                    brickDateStatusAlignmentLayout, brickDateStatusAlignmentDropdown);
        }

        private void bindGpsBlock() {
            brickGpsShowSatelliteBadge.setChecked(prefs.gps.showSatelliteBadge.get());
            brickGpsShowSatelliteBadge.setOnCheckedChangeListener((v, c) -> {
                prefs.gps.showSatelliteBadge.set(c);
                notifyService();
            });
        }

        private void bindBluetoothBlock() {
            brickBluetoothShowDeviceCountBadge.setChecked(prefs.bluetooth.showDeviceCountBadge.get());
            brickBluetoothShowDeviceCountBadge.setOnCheckedChangeListener((v, c) -> {
                prefs.bluetooth.showDeviceCountBadge.set(c);
                notifyService();
            });
        }

        private void bindMediaBlock() {
            // ============================ Общие ============================
            brickMediaShowSource.setChecked(prefs.media.showSource.get());
            brickMediaShowSource.setOnCheckedChangeListener((v, c) -> {
                prefs.media.showSource.set(c);
                refreshMediaSourceSectionVisibility();
                notifyService();
            });
            brickMediaTitleFirst.setChecked(prefs.media.titleFirst.get());
            brickMediaTitleFirst.setOnCheckedChangeListener((v, c) -> {
                prefs.media.titleFirst.set(c);
                notifyService();
            });
            brickMediaMarqueeEnabled.setChecked(prefs.media.marqueeEnabled.get());
            brickMediaMarqueeEnabled.setOnCheckedChangeListener((v, c) -> {
                prefs.media.marqueeEnabled.set(c);
                notifyService();
            });
            brickMediaProgressBarEnabled.setChecked(prefs.media.progressBarEnabled.get());
            brickMediaProgressBarEnabled.setOnCheckedChangeListener((v, c) -> {
                prefs.media.progressBarEnabled.set(c);
                notifyService();
            });
            refreshMediaSourceSectionVisibility();

            // Upper bound = 80% of the current screen width — gives a useful range on both phones
            // and car head units without locking it to the XML default.
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            float upper = Math.max(brickMediaMaxWidthSlider.getValueFrom() + 1F, screenW * 0.8F);
            brickMediaMaxWidthSlider.setValueTo(upper);
            bindIntSlider(brickMediaMaxWidthSlider, prefs.media.maxWidth, sizeFormatter());

            bindIntSlider(brickMediaMarginStartSlider, prefs.media.marginStart, sizeFormatter());
            bindIntSlider(brickMediaMarginEndSlider, prefs.media.marginEnd, sizeFormatter());
            bindIntSlider(brickMediaAdjustYSlider, prefs.media.adjustY, offsetFormatter());
            bindIntSlider(brickMediaLineGapSlider, prefs.media.lineGap, sizeFormatter());
            bindInBlockStatusAlignment(BrickType.MEDIA,
                    brickMediaStatusAlignmentLayout, brickMediaStatusAlignmentDropdown);

            // ============================ Источник ============================
            bindIntSlider(brickMediaSourceFontSizeSlider, prefs.media.sourceFontSize, sizeFormatter());
            bindIntSlider(brickMediaSourceOutlineAlphaSlider, prefs.media.sourceOutlineAlpha, plainFormatter());
            bindIntSlider(brickMediaSourceOutlineWidthSlider, prefs.media.sourceOutlineWidth, sizeFormatter());
            bindIntSlider(brickMediaSourceContentAlphaSlider, prefs.media.sourceContentAlpha, plainFormatter());
            ViewBinder.linkPairDisableOnZero(
                    brickMediaSourceOutlineAlphaSlider, brickMediaSourceOutlineWidthSlider);
            bindFontFamilyDropdown(brickMediaSourceFontFamilyDropdown, prefs.media.sourceFontFamily);
            bindFontStyleToggles(brickMediaSourceFontStyleGroup,
                    brickMediaSourceFontBold, brickMediaSourceFontItalic,
                    prefs.media.sourceFontBold, prefs.media.sourceFontItalic);
            bindAlignmentDropdown(brickMediaSourceAlignmentDropdown, prefs.media.sourceAlignment);

            // ============================ Композиция ============================
            // Title uses the inherited TextBrickPrefs fields (fontSize, outline, contentAlpha,
            // fontFamily, fontBold/Italic) so existing presets keep working unchanged.
            bindIntSlider(brickMediaTitleFontSizeSlider, prefs.media.fontSize, sizeFormatter());
            bindIntSlider(brickMediaTitleOutlineAlphaSlider, prefs.media.outlineAlpha, plainFormatter());
            bindIntSlider(brickMediaTitleOutlineWidthSlider, prefs.media.outlineWidth, sizeFormatter());
            bindIntSlider(brickMediaTitleContentAlphaSlider, prefs.media.contentAlpha, plainFormatter());
            ViewBinder.linkPairDisableOnZero(
                    brickMediaTitleOutlineAlphaSlider, brickMediaTitleOutlineWidthSlider);
            bindFontFamilyDropdown(brickMediaTitleFontFamilyDropdown, prefs.media.fontFamily);
            bindFontStyleToggles(brickMediaTitleFontStyleGroup,
                    brickMediaTitleFontBold, brickMediaTitleFontItalic,
                    prefs.media.fontBold, prefs.media.fontItalic);
            bindAlignmentDropdown(brickMediaTitleAlignmentDropdown, prefs.media.alignment);

            // ============================ Длительность ============================
            brickMediaShowDuration.setChecked(prefs.media.showDuration.get());
            brickMediaShowDuration.setOnCheckedChangeListener((v, c) -> {
                prefs.media.showDuration.set(c);
                refreshMediaDurationSectionVisibility();
                notifyService();
            });
            bindIntSlider(brickMediaDurationFontSizeSlider, prefs.media.durationFontSize, sizeFormatter());
            bindIntSlider(brickMediaDurationContentAlphaSlider, prefs.media.durationContentAlpha, plainFormatter());
            bindIntSlider(brickMediaDurationOutlineAlphaSlider, prefs.media.durationOutlineAlpha, plainFormatter());
            bindIntSlider(brickMediaDurationOutlineWidthSlider, prefs.media.durationOutlineWidth, sizeFormatter());
            ViewBinder.linkPairDisableOnZero(
                    brickMediaDurationOutlineAlphaSlider, brickMediaDurationOutlineWidthSlider);
            refreshMediaDurationSectionVisibility();

            brickMediaPermissionButton.setOnClickListener(v -> {
                if (Permissions.isNotificationAccessGranted(activity)) {
                    Toast.makeText(activity,
                            activity.getString(R.string.notification_access_required),
                            Toast.LENGTH_SHORT).show();
                }
                try {
                    activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception ignored) {
                }
            });
        }


        private void showDateBlock(boolean show) {
            brickDateBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showGpsBlock(boolean show) {
            brickGpsBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showBluetoothBlock(boolean show) {
            brickBluetoothBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showMediaBlock(boolean show) {
            brickMediaBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void bindIntSlider(Slider slider, Preferences.Int pref, LabelFormatter formatter) {
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

    private LabelFormatter sizeFormatter() {
        return value -> activity.getString(R.string.size_value_format, (int) value);
    }

    private LabelFormatter plainFormatter() {
        return value -> activity.getString(R.string.color_component_value_format, (int) value);
    }

    private LabelFormatter offsetFormatter() {
        return value -> {
            int v = (int) value;
            return (v > 0 ? "+" : "") + activity.getString(R.string.size_value_format, v);
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
