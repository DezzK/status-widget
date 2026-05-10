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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
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

    private void notifyService() {
        if (WidgetService.isRunning()) {
            WidgetService.getInstance().applyPreferences();
        }
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
        final Slider brickMarginStartSlider;
        final Slider brickMarginEndSlider;
        final LinearLayout brickAdjustYBlock;
        final Slider brickAdjustYSlider;
        final LinearLayout brickDateBlock;
        final MaterialSwitch brickDateShowDate;
        final MaterialSwitch brickDateShowDayOfWeek;
        final MaterialSwitch brickDateShowFullName;
        final MaterialSwitch brickDateBeforeDayOfWeek;
        final MaterialSwitch brickDateOneLineLayout;
        final MaterialAutoCompleteTextView brickDateAlignmentDropdown;
        final LinearLayout brickGpsBlock;
        final MaterialSwitch brickGpsShowSatelliteBadge;
        final LinearLayout brickMediaBlock;
        final MaterialButton brickMediaPermissionButton;
        final MaterialButton brickRemoveButton;

        boolean expanded;

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
            brickMarginStartSlider = itemView.findViewById(R.id.brickMarginStartSlider);
            brickMarginEndSlider = itemView.findViewById(R.id.brickMarginEndSlider);
            brickAdjustYBlock = itemView.findViewById(R.id.brickAdjustYBlock);
            brickAdjustYSlider = itemView.findViewById(R.id.brickAdjustYSlider);
            brickDateBlock = itemView.findViewById(R.id.brickDateBlock);
            brickDateShowDate = itemView.findViewById(R.id.brickDateShowDate);
            brickDateShowDayOfWeek = itemView.findViewById(R.id.brickDateShowDayOfWeek);
            brickDateShowFullName = itemView.findViewById(R.id.brickDateShowFullName);
            brickDateBeforeDayOfWeek = itemView.findViewById(R.id.brickDateBeforeDayOfWeek);
            brickDateOneLineLayout = itemView.findViewById(R.id.brickDateOneLineLayout);
            brickDateAlignmentDropdown = itemView.findViewById(R.id.brickDateAlignmentDropdown);
            brickGpsBlock = itemView.findViewById(R.id.brickGpsBlock);
            brickGpsShowSatelliteBadge = itemView.findViewById(R.id.brickGpsShowSatelliteBadge);
            brickMediaBlock = itemView.findViewById(R.id.brickMediaBlock);
            brickMediaPermissionButton = itemView.findViewById(R.id.brickMediaPermissionButton);
            brickRemoveButton = itemView.findViewById(R.id.brickRemoveButton);
        }

        @SuppressLint("ClickableViewAccessibility")
        void bind(BrickType type) {
            brickTitle.setText(titleFor(type));
            brickHeader.setOnClickListener(v -> setExpanded(!expanded));
            brickExpand.setOnClickListener(v -> setExpanded(!expanded));
            brickDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                    itemTouchHelper.startDrag(this);
                }
                return false;
            });
            brickRemoveButton.setOnClickListener(v -> removeBrick(type));

            // Reset listeners before rebinding (recycled holders).
            brickSizeSlider.clearOnChangeListeners();
            brickOutlineAlphaSlider.clearOnChangeListeners();
            brickOutlineWidthSlider.clearOnChangeListeners();
            brickMarginStartSlider.clearOnChangeListeners();
            brickMarginEndSlider.clearOnChangeListeners();
            brickAdjustYSlider.clearOnChangeListeners();
            brickDateShowDate.setOnCheckedChangeListener(null);
            brickDateShowDayOfWeek.setOnCheckedChangeListener(null);
            brickDateShowFullName.setOnCheckedChangeListener(null);
            brickDateBeforeDayOfWeek.setOnCheckedChangeListener(null);
            brickDateOneLineLayout.setOnCheckedChangeListener(null);
            brickGpsShowSatelliteBadge.setOnCheckedChangeListener(null);

            switch (type) {
                case TIME:
                    bindTextBrick(prefs.time);
                    showAdjustY(true);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showMediaBlock(false);
                    break;
                case DATE:
                    bindTextBrick(prefs.date);
                    showAdjustY(true);
                    showDateBlock(true);
                    bindDateBlock();
                    showGpsBlock(false);
                    showMediaBlock(false);
                    break;
                case MEDIA:
                    bindTextBrick(prefs.media);
                    showAdjustY(false);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showMediaBlock(true);
                    bindMediaBlock();
                    break;
                case WIFI:
                    bindIconBrick(prefs.wifi);
                    showAdjustY(false);
                    showDateBlock(false);
                    showGpsBlock(false);
                    showMediaBlock(false);
                    break;
                case GPS:
                    bindIconBrick(prefs.gps);
                    showAdjustY(false);
                    showDateBlock(false);
                    showGpsBlock(true);
                    bindGpsBlock();
                    showMediaBlock(false);
                    break;
            }

            applyExpandState();
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
                default:
                    return "";
            }
        }

        private void setExpanded(boolean expand) {
            expanded = expand;
            applyExpandState();
        }

        private void applyExpandState() {
            brickPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            brickExpand.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        }

        private void bindTextBrick(Preferences.TextBrickPrefs p) {
            brickSizeLabel.setText(R.string.brick_font_size);
            // Time gets a wider range because it is typically the largest element.
            brickSizeSlider.setValueFrom(10);
            brickSizeSlider.setValueTo(500);
            bindIntSlider(brickSizeSlider, p.fontSize, sizeFormatter());
            bindIntSlider(brickOutlineAlphaSlider, p.outlineAlpha, plainFormatter());
            bindIntSlider(brickOutlineWidthSlider, p.outlineWidth, sizeFormatter());
            bindIntSlider(brickMarginStartSlider, p.marginStart, sizeFormatter());
            bindIntSlider(brickMarginEndSlider, p.marginEnd, sizeFormatter());
            if (p instanceof Preferences.SingleLineTextBrickPrefs) {
                Preferences.SingleLineTextBrickPrefs sp = (Preferences.SingleLineTextBrickPrefs) p;
                bindIntSlider(brickAdjustYSlider, sp.adjustY, offsetFormatter());
            }
        }

        private void bindIconBrick(Preferences.IconBrickPrefs p) {
            brickSizeLabel.setText(R.string.brick_size);
            brickSizeSlider.setValueFrom(10);
            brickSizeSlider.setValueTo(600);
            bindIntSlider(brickSizeSlider, p.size, sizeFormatter());
            bindIntSlider(brickOutlineAlphaSlider, p.outlineAlpha, plainFormatter());
            bindIntSlider(brickOutlineWidthSlider, p.outlineWidth, sizeFormatter());
            bindIntSlider(brickMarginStartSlider, p.marginStart, sizeFormatter());
            bindIntSlider(brickMarginEndSlider, p.marginEnd, sizeFormatter());
        }

        private void bindDateBlock() {
            brickDateShowDate.setChecked(prefs.date.showDate.get());
            brickDateShowDate.setOnCheckedChangeListener((v, c) -> {
                prefs.date.showDate.set(c);
                notifyService();
            });
            brickDateShowDayOfWeek.setChecked(prefs.date.showDayOfWeek.get());
            brickDateShowDayOfWeek.setOnCheckedChangeListener((v, c) -> {
                prefs.date.showDayOfWeek.set(c);
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
        }

        private void bindGpsBlock() {
            brickGpsShowSatelliteBadge.setChecked(prefs.gps.showSatelliteBadge.get());
            brickGpsShowSatelliteBadge.setOnCheckedChangeListener((v, c) -> {
                prefs.gps.showSatelliteBadge.set(c);
                notifyService();
            });
        }

        private void bindMediaBlock() {
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

        private void showAdjustY(boolean show) {
            brickAdjustYBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showDateBlock(boolean show) {
            brickDateBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showGpsBlock(boolean show) {
            brickGpsBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private void showMediaBlock(boolean show) {
            brickMediaBlock.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private static void bindIntSlider(Slider slider, Preferences.Int pref, LabelFormatter formatter) {
        float current = clamp(pref.get(), slider.getValueFrom(), slider.getValueTo());
        slider.setValue(current);
        slider.setLabelFormatter(formatter);
        slider.addOnChangeListener((s, value, fromUser) -> {
            pref.set((int) value);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        });
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
