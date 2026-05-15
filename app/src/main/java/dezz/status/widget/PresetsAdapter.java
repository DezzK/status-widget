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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for {@link PresetsActivity}'s single list of both bundled and user
 * presets. Each card is tappable (= apply); user presets additionally show a trailing overflow
 * button for the rename / export / delete actions.
 */
final class PresetsAdapter extends RecyclerView.Adapter<PresetsAdapter.Holder> {

    interface Callback {
        void onApply(Entry entry);
        void onRename(Entry entry);
        void onExport(Entry entry);
        void onDelete(Entry entry);
    }

    /**
     * One row in the list. Exactly one of {@link #bundled} / {@link #user} is non-null —
     * which one decides the overflow visibility and the icon variant.
     */
    static final class Entry {
        final String name;
        @Nullable final String subtitle;
        @Nullable final Presets.Preset bundled;
        @Nullable final UserPresets.UserPreset user;

        private Entry(String name, @Nullable String subtitle,
                      @Nullable Presets.Preset bundled,
                      @Nullable UserPresets.UserPreset user) {
            this.name = name;
            this.subtitle = subtitle;
            this.bundled = bundled;
            this.user = user;
        }

        static Entry bundled(Context ctx, Presets.Preset p) {
            String subtitle = ctx.getString(R.string.preset_bundled_subtitle_prefix,
                    ctx.getString(p.descRes));
            return new Entry(ctx.getString(p.nameRes), subtitle, p, null);
        }

        static Entry user(Context ctx, UserPresets.UserPreset u) {
            return new Entry(u.name, ctx.getString(R.string.preset_user_subtitle), null, u);
        }

        boolean isUser() {
            return user != null;
        }
    }

    private final Context context;
    private final Callback callback;
    private final List<Entry> items = new ArrayList<>();

    PresetsAdapter(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    void submit(List<Entry> entries) {
        items.clear();
        items.addAll(entries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.preset_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Entry entry = items.get(position);
        holder.name.setText(entry.name);
        if (entry.subtitle == null || entry.subtitle.isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(entry.subtitle);
        }
        // Bundled vs user presets get visually distinct leading icons.
        holder.icon.setImageResource(entry.isUser()
                ? R.drawable.ic_save_preset
                : R.drawable.ic_preset);
        // Action row only makes sense for user-created presets; bundled presets are
        // read-only — tapping the card is the only thing you can do with them.
        holder.actions.setVisibility(entry.isUser() ? View.VISIBLE : View.GONE);
        holder.card.setOnClickListener(v -> callback.onApply(entry));
        holder.actionRename.setOnClickListener(v -> callback.onRename(entry));
        holder.actionExport.setOnClickListener(v -> callback.onExport(entry));
        holder.actionDelete.setOnClickListener(v -> callback.onDelete(entry));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final TextView name;
        final TextView subtitle;
        final View actions;
        final MaterialButton actionRename;
        final MaterialButton actionExport;
        final MaterialButton actionDelete;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            icon = itemView.findViewById(R.id.presetIcon);
            name = itemView.findViewById(R.id.presetName);
            subtitle = itemView.findViewById(R.id.presetSubtitle);
            actions = itemView.findViewById(R.id.presetActions);
            actionRename = itemView.findViewById(R.id.presetActionRename);
            actionExport = itemView.findViewById(R.id.presetActionExport);
            actionDelete = itemView.findViewById(R.id.presetActionDelete);
        }
    }
}
