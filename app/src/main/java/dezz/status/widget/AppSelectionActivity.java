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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.databinding.ActivityAppSelectionBinding;

public class AppSelectionActivity extends AppCompatActivity {
    private ActivityAppSelectionBinding binding;
    private Preferences prefs;
    private Set<String> selected = new HashSet<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppSelectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new Preferences(this);
        selected = prefs.hideInPackages.get();

        adapter = new AppAdapter();
        binding.appList.setLayoutManager(new LinearLayoutManager(this));
        binding.appList.setAdapter(adapter);

        new LoadAppsTask().execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.hideInPackages.set(selected);
        if (WidgetService.isRunning()) {
            WidgetService.getInstance().applyPreferences();
        }
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppEntry>> {
        @Override
        protected List<AppEntry> doInBackground(Void... voids) {
            PackageManager pm = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchableApps = pm.queryIntentActivities(launcherIntent, 0);

            // Home screens / launchers usually only declare CATEGORY_HOME, so they wouldn't appear
            // in the list above — query them separately.
            Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> homeApps = pm.queryIntentActivities(homeIntent, 0);

            List<AppEntry> result = new ArrayList<>(launchableApps.size() + homeApps.size());
            String selfPackage = getPackageName();
            HashSet<String> seen = new HashSet<>();
            for (ResolveInfo info : launchableApps) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(selfPackage) || !seen.add(pkg)) {
                    continue;
                }
                CharSequence label = info.loadLabel(pm);
                Drawable icon = info.loadIcon(pm);
                result.add(new AppEntry(pkg, label != null ? label.toString() : pkg, icon));
            }
            for (ResolveInfo info : homeApps) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(selfPackage) || !seen.add(pkg)) {
                    continue;
                }
                CharSequence label = info.loadLabel(pm);
                Drawable icon = info.loadIcon(pm);
                result.add(new AppEntry(pkg, label != null ? label.toString() : pkg, icon));
            }

            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY);
            Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
            return result;
        }

        @Override
        protected void onPostExecute(List<AppEntry> result) {
            apps.clear();
            apps.addAll(result);
            adapter.notifyDataSetChanged();
            binding.appSelectionProgress.setVisibility(View.GONE);
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppViewHolder> {
        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.app_selection_item, parent, false);
            return new AppViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
            AppEntry entry = apps.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.name.setText(entry.label);
            holder.checkBox.setChecked(selected.contains(entry.packageName));
            holder.itemView.setOnClickListener(v -> {
                boolean nowChecked = !selected.contains(entry.packageName);
                if (nowChecked) {
                    selected.add(entry.packageName);
                } else {
                    selected.remove(entry.packageName);
                }
                holder.checkBox.setChecked(nowChecked);
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private static final class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final CheckBox checkBox;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.appIcon);
            name = itemView.findViewById(R.id.appName);
            checkBox = itemView.findViewById(R.id.appCheckBox);
        }
    }
}
