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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The brick list on the settings screen. Every card inflates the same shared skeleton
 * ({@code brick_item.xml}) and, for a brick that has one, its own block layout into
 * {@code brickTypeBlockSlot} — see {@link BrickBlock}.
 *
 * <p>There is one view type per {@link BrickType}, so a holder serves exactly one brick for its
 * whole life. That is what lets the block layout be chosen once at creation, and what lets each
 * {@link BrickBlockBinder} capture its concrete prefs object instead of casting on every bind.
 */
public class BrickListAdapter extends RecyclerView.Adapter<BrickListAdapter.BrickViewHolder> {
    public interface OrderChangedListener {
        void onOrderChanged();
    }

    private final AppCompatActivity activity;
    private final Preferences prefs;
    private final BrickBinderContext binderCtx;
    private final OrderChangedListener orderChangedListener;
    private final List<BrickType> bricks;
    private ItemTouchHelper itemTouchHelper;
    /** Currently expanded brick — only one panel is open at a time. {@code null} = all collapsed. */
    @Nullable
    private BrickType expandedType;

    public BrickListAdapter(AppCompatActivity activity, Preferences prefs,
                            OrderChangedListener orderChangedListener) {
        this.activity = activity;
        this.prefs = prefs;
        this.binderCtx = new BrickBinderContext(activity, prefs, this::notifyService);
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

    /**
     * Move, not swap: {@code notifyItemMoved} means "this one item travelled", so swapping the
     * endpoints leaves every card in between showing the wrong brick until something forces a
     * full rebind. With one layout per brick type that is now the wrong SHAPE, not just the
     * wrong text.
     */
    public void moveBrick(int fromPos, int toPos) {
        bricks.add(toPos, bricks.remove(fromPos));
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

    private String brickTitleString(BrickType type) {
        return activity.getString(type.titleRes());
    }

    private String hideTitleFor(BrickType type) {
        return activity.getString(R.string.brick_hide_in_apps_title, brickTitleString(type));
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

    /**
     * Rebind the cards of car-fed bricks. Their blocks may describe what the car reports (the fuel
     * block's tank-capacity hint), and that answer changes once the car integration settles.
     */
    public void notifyCarAvailabilityChanged() {
        for (int i = 0; i < bricks.size(); i++) {
            if (bricks.get(i).isCarSpecific()) notifyItemChanged(i);
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

    @Override
    public int getItemViewType(int position) {
        return bricks.get(position).ordinal();
    }

    @NonNull
    @Override
    public BrickViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        BrickType type = BrickType.byOrdinal(viewType);
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View card = inflater.inflate(R.layout.brick_item, parent, false);

        BrickBlockBinder blockBinder = null;
        BrickBlock block = BrickBlock.forType(type);
        if (block != null) {
            ViewGroup slot = card.findViewById(R.id.brickTypeBlockSlot);
            // attachToRoot=false so the binder gets the block's own root and its lookups stay
            // scoped to it; inflate(..., true) would hand back the slot instead.
            View blockRoot = inflater.inflate(block.layoutRes, slot, false);
            slot.addView(blockRoot);
            blockBinder = block.newBinder(binderCtx, blockRoot);
        }
        return new BrickViewHolder(card, type, blockBinder);
    }

    @Override
    public void onBindViewHolder(@NonNull BrickViewHolder holder, int position) {
        holder.bind();
    }

    class BrickViewHolder extends RecyclerView.ViewHolder {
        /** Fixed for the holder's whole life — the view type IS the brick type. */
        final BrickType type;
        private final Preferences.BrickPrefs p;
        private final EnumSet<BrickControl> controls;
        @Nullable
        private final BrickBlockBinder blockBinder;

        // Header.
        private final TextView brickTitle;
        private final ImageView brickDragHandle;
        private final ImageView brickExpand;
        private final LinearLayout brickHeader;
        private final LinearLayout brickPanel;
        // Generic rows.
        private final LinearLayout brickSizeAdjustRow;
        private final LinearLayout brickSizeColumn;
        private final LinearLayout brickAdjustYColumn;
        private final TextView brickSizeLabel;
        private final Slider brickSizeSlider;
        private final Slider brickAdjustYSlider;
        private final LinearLayout brickTableRow;
        private final Slider brickTableRowsSlider;
        private final Slider brickTableCellGapSlider;
        private final LinearLayout brickOutlineRow;
        private final Slider brickOutlineAlphaSlider;
        private final Slider brickOutlineWidthSlider;
        private final LinearLayout brickContentAlphaRow;
        private final Slider brickContentAlphaSlider;
        private final LinearLayout brickMarginRow;
        private final Slider brickMarginStartSlider;
        private final Slider brickMarginEndSlider;
        // Font block.
        private final LinearLayout brickFontBlock;
        private final MaterialAutoCompleteTextView brickFontFamilyDropdown;
        private final MaterialButtonToggleGroup brickFontStyleGroup;
        private final MaterialButton brickFontBold;
        private final MaterialButton brickFontItalic;
        // Shared status-bar alignment.
        private final TextInputLayout brickStatusAlignmentLayout;
        private final MaterialAutoCompleteTextView brickStatusAlignmentDropdown;
        // Hide-in-apps.
        private final LinearLayout brickHideOwnBlock;
        private final MaterialButton brickHideInAppsButton;
        private final TextView brickHideApplyToLabel;
        private final ChipGroup brickHideApplyToChips;
        private final LinearLayout brickHideInheritedBlock;
        private final TextView brickHideInheritedHint;
        private final MaterialButton brickHideUseOwnButton;
        /**
         * One switch lives inside each hide block so it sits visually next to the relevant
         * button. Both are bound to the same {@code hideKeepsSpace} pref; only the one whose
         * parent block is visible is interactable at any given time.
         */
        private final MaterialSwitch brickHideKeepsSpaceOwnSwitch;
        private final MaterialSwitch brickHideKeepsSpaceInheritedSwitch;
        // Footer.
        private final MaterialButton brickResetButton;
        private final MaterialButton brickRemoveButton;

        BrickViewHolder(@NonNull View itemView, BrickType type,
                        @Nullable BrickBlockBinder blockBinder) {
            super(itemView);
            this.type = type;
            this.p = prefs.brickPrefs(type);
            this.controls = p.controls();
            this.blockBinder = blockBinder;

            brickTitle = itemView.findViewById(R.id.brickTitle);
            brickDragHandle = itemView.findViewById(R.id.brickDragHandle);
            brickExpand = itemView.findViewById(R.id.brickExpand);
            brickHeader = itemView.findViewById(R.id.brickHeader);
            brickPanel = itemView.findViewById(R.id.brickPanel);
            brickSizeAdjustRow = itemView.findViewById(R.id.brickSizeAdjustRow);
            brickSizeColumn = itemView.findViewById(R.id.brickSizeColumn);
            brickAdjustYColumn = itemView.findViewById(R.id.brickAdjustYColumn);
            brickSizeLabel = itemView.findViewById(R.id.brickSizeLabel);
            brickSizeSlider = itemView.findViewById(R.id.brickSizeSlider);
            brickAdjustYSlider = itemView.findViewById(R.id.brickAdjustYSlider);
            brickTableRow = itemView.findViewById(R.id.brickTableRow);
            brickTableRowsSlider = itemView.findViewById(R.id.brickTableRowsSlider);
            brickTableCellGapSlider = itemView.findViewById(R.id.brickTableCellGapSlider);
            brickOutlineRow = itemView.findViewById(R.id.brickOutlineRow);
            brickOutlineAlphaSlider = itemView.findViewById(R.id.brickOutlineAlphaSlider);
            brickOutlineWidthSlider = itemView.findViewById(R.id.brickOutlineWidthSlider);
            brickContentAlphaRow = itemView.findViewById(R.id.brickContentAlphaRow);
            brickContentAlphaSlider = itemView.findViewById(R.id.brickContentAlphaSlider);
            brickMarginRow = itemView.findViewById(R.id.brickMarginRow);
            brickMarginStartSlider = itemView.findViewById(R.id.brickMarginStartSlider);
            brickMarginEndSlider = itemView.findViewById(R.id.brickMarginEndSlider);
            brickFontBlock = itemView.findViewById(R.id.brickFontBlock);
            brickFontFamilyDropdown = itemView.findViewById(R.id.brickFontFamilyDropdown);
            brickFontStyleGroup = itemView.findViewById(R.id.brickFontStyleGroup);
            brickFontBold = itemView.findViewById(R.id.brickFontBold);
            brickFontItalic = itemView.findViewById(R.id.brickFontItalic);
            brickStatusAlignmentLayout = itemView.findViewById(R.id.brickStatusAlignmentLayout);
            brickStatusAlignmentDropdown = itemView.findViewById(R.id.brickStatusAlignmentDropdown);
            brickHideOwnBlock = itemView.findViewById(R.id.brickHideOwnBlock);
            brickHideInAppsButton = itemView.findViewById(R.id.brickHideInAppsButton);
            brickHideApplyToLabel = itemView.findViewById(R.id.brickHideApplyToLabel);
            brickHideApplyToChips = itemView.findViewById(R.id.brickHideApplyToChips);
            brickHideInheritedBlock = itemView.findViewById(R.id.brickHideInheritedBlock);
            brickHideInheritedHint = itemView.findViewById(R.id.brickHideInheritedHint);
            brickHideUseOwnButton = itemView.findViewById(R.id.brickHideUseOwnButton);
            brickHideKeepsSpaceOwnSwitch = itemView.findViewById(R.id.brickHideKeepsSpaceOwnSwitch);
            brickHideKeepsSpaceInheritedSwitch =
                    itemView.findViewById(R.id.brickHideKeepsSpaceInheritedSwitch);
            brickResetButton = itemView.findViewById(R.id.brickResetButton);
            brickRemoveButton = itemView.findViewById(R.id.brickRemoveButton);

            applyControlVisibility();
            bindStaticListeners();
        }

        /**
         * Which shared controls this card shows never changes: {@code controls()} is a pure
         * function of the prefs class and the holder serves one brick type for life. So it runs
         * once, here, instead of on every rebind.
         *
         * <p>Keep it exhaustive — one line per optional container in {@code brick_item.xml}. The
         * one exception is {@code brickStatusAlignmentLayout}, whose visibility also depends on
         * {@code prefs.widgetMode} and so is driven from {@link #bindViews()}.
         */
        private void applyControlVisibility() {
            brickSizeAdjustRow.setVisibility(
                    controls.contains(BrickControl.COL_SIZE)
                            || controls.contains(BrickControl.COL_ADJUST_Y)
                            ? View.VISIBLE : View.GONE);
            brickSizeColumn.setVisibility(visibilityOf(BrickControl.COL_SIZE));
            brickAdjustYColumn.setVisibility(visibilityOf(BrickControl.COL_ADJUST_Y));
            brickTableRow.setVisibility(visibilityOf(BrickControl.ROW_TABLE));
            brickOutlineRow.setVisibility(visibilityOf(BrickControl.ROW_OUTLINE));
            brickContentAlphaRow.setVisibility(visibilityOf(BrickControl.ROW_CONTENT_ALPHA));
            brickMarginRow.setVisibility(visibilityOf(BrickControl.ROW_MARGIN));
            brickFontBlock.setVisibility(visibilityOf(BrickControl.BLOCK_FONT));
        }

        private int visibilityOf(BrickControl control) {
            return controls.contains(control) ? View.VISIBLE : View.GONE;
        }

        /** Listeners that depend on nothing but the holder's own (fixed) brick type. */
        @SuppressLint("ClickableViewAccessibility")
        private void bindStaticListeners() {
            brickTitle.setText(type.titleRes());
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
        }

        /**
         * Same contract as {@link BrickBlockBinder#rebind()} and deliberately {@code final}: the
         * slider and toggle-group listeners APPEND, and every visible card is rebound whenever
         * the user expands a different brick.
         */
        final void bind() {
            clearListeners();
            bindViews();
            if (blockBinder != null) {
                blockBinder.rebind();
            }
            applyExpandState();
        }

        private void clearListeners() {
            brickSizeSlider.clearOnChangeListeners();
            brickAdjustYSlider.clearOnChangeListeners();
            brickTableRowsSlider.clearOnChangeListeners();
            brickTableCellGapSlider.clearOnChangeListeners();
            brickOutlineAlphaSlider.clearOnChangeListeners();
            brickOutlineWidthSlider.clearOnChangeListeners();
            brickContentAlphaSlider.clearOnChangeListeners();
            brickMarginStartSlider.clearOnChangeListeners();
            brickMarginEndSlider.clearOnChangeListeners();
            brickHideKeepsSpaceOwnSwitch.setOnCheckedChangeListener(null);
            brickHideKeepsSpaceInheritedSwitch.setOnCheckedChangeListener(null);
        }

        private void bindViews() {
            if (controls.contains(BrickControl.COL_SIZE)) {
                brickSizeLabel.setText(p.sizeLabelRes);
                brickSizeSlider.setContentDescription(activity.getString(p.sizeLabelRes));
                brickSizeSlider.setValueFrom(p.sizeMin);
                brickSizeSlider.setValueTo(p.sizeMax);
                binderCtx.bindIntSlider(brickSizeSlider, p.size, binderCtx.sizeFormatter());
            }
            if (controls.contains(BrickControl.COL_ADJUST_Y)) {
                binderCtx.bindIntSlider(brickAdjustYSlider, p.adjustY, binderCtx.offsetFormatter());
            }
            if (controls.contains(BrickControl.ROW_TABLE)) {
                // Safe cast: ROW_TABLE has exactly one origin, TableBrickPrefs.controls().
                Preferences.TableBrickPrefs tp = (Preferences.TableBrickPrefs) p;
                binderCtx.bindIntSlider(brickTableRowsSlider, tp.rows,
                        binderCtx.plainFormatter());
                binderCtx.bindIntSlider(brickTableCellGapSlider, tp.cellGap,
                        binderCtx.sizeFormatter());
            }
            if (controls.contains(BrickControl.ROW_OUTLINE)) {
                binderCtx.bindIntSlider(brickOutlineAlphaSlider, p.outlineAlpha,
                        binderCtx.plainFormatter());
                binderCtx.bindIntSlider(brickOutlineWidthSlider, p.outlineWidth,
                        binderCtx.sizeFormatter());
                // After both values are seeded — the linker reads them to decide enabled state.
                ViewBinder.linkPairDisableOnZero(brickOutlineAlphaSlider, brickOutlineWidthSlider);
            }
            if (controls.contains(BrickControl.ROW_CONTENT_ALPHA)) {
                binderCtx.bindIntSlider(brickContentAlphaSlider, p.contentAlpha,
                        binderCtx.plainFormatter());
            }
            if (controls.contains(BrickControl.ROW_MARGIN)) {
                binderCtx.bindIntSlider(brickMarginStartSlider, p.marginStart,
                        binderCtx.sizeFormatter());
                binderCtx.bindIntSlider(brickMarginEndSlider, p.marginEnd,
                        binderCtx.sizeFormatter());
            }
            if (controls.contains(BrickControl.BLOCK_FONT)) {
                // Safe while BLOCK_FONT is added by TextBrickPrefs.controls() and nowhere else —
                // see the note on the constant. The font block is the one per-type control that
                // lives on the shared card, because the card has a single per-type slot and it is
                // already taken by the brick's own block.
                Preferences.TextBrickPrefs textPrefs = (Preferences.TextBrickPrefs) p;
                binderCtx.bindFontFamilyDropdown(brickFontFamilyDropdown, textPrefs.fontFamily);
                binderCtx.bindFontStyleToggles(brickFontStyleGroup, brickFontBold, brickFontItalic,
                        textPrefs.fontBold, textPrefs.fontItalic);
            }
            if (controls.contains(BrickControl.SHARED_STATUS_ALIGNMENT)) {
                binderCtx.bindStatusAlignmentDropdown(p.statusAlignment,
                        brickStatusAlignmentLayout, brickStatusAlignmentDropdown);
            } else {
                brickStatusAlignmentLayout.setVisibility(View.GONE);
            }
            bindHideBlock();
        }

        private void bindHideBlock() {
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

                bindApplyToChips();
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

        private void bindApplyToChips() {
            brickHideApplyToChips.removeAllViews();
            int otherCount = 0;
            for (BrickType candidate : BrickType.values()) {
                if (candidate == type) continue;
                otherCount++;
                Chip chip = new Chip(activity);
                chip.setText(brickTitleString(candidate));
                chip.setCheckable(true);
                String candidateSource = prefs.hideSourceFor(candidate).get();
                boolean inheritsFromUs = type.name().equals(candidateSource);
                chip.setChecked(inheritsFromUs);
                // A brick that already shares its list with a different parent is OK to retarget;
                // but a brick that itself has children would create a 2-level chain — disallow.
                boolean candidateHasChildren = brickHasChildren(candidate);
                chip.setEnabled(inheritsFromUs || !candidateHasChildren);
                chip.setOnClickListener(v -> {
                    if (chip.isChecked()) {
                        prefs.hideSourceFor(candidate).set(type.name());
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

        private void applyExpandState() {
            boolean expanded = (type == expandedType);
            brickPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            brickExpand.setImageResource(
                    expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        }
    }
}
