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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
    private static final String TAG = "AppSelectionActivity";

    /** SharedPreferences key (StringSet) of the brick-specific or global hide list to edit. */
    public static final String EXTRA_PREF_KEY = "prefKey";
    /** Optional pre-formatted toolbar title. */
    public static final String EXTRA_TITLE = "title";

    private ActivityAppSelectionBinding binding;
    private Preferences prefs;
    private Preferences.StringSet target;
    private Set<String> selected = new HashSet<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            EdgeToEdge.enable(this);
        } catch (Throwable t) {
            Log.w(TAG, "EdgeToEdge.enable failed", t);
        }
        super.onCreate(savedInstanceState);
        try {
            binding = ActivityAppSelectionBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, 0);
                return windowInsets;
            });
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(bars.left, 0, bars.right, 0);
                ((androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                        v.getLayoutParams()).bottomMargin = bars.bottom;
                return windowInsets;
            });

            binding.bottomBar.setNavigationOnClickListener(v -> finish());

            prefs = new Preferences(this);
            String prefKey = getIntent().getStringExtra(EXTRA_PREF_KEY);
            target = (prefKey != null)
                    ? new Preferences.StringSet(prefs, prefKey)
                    : prefs.hideInPackages;
            String customTitle = getIntent().getStringExtra(EXTRA_TITLE);
            if (customTitle != null && !customTitle.isEmpty()) {
                binding.titleText.setText(customTitle);
            }
            selected = target.get();

            adapter = new AppAdapter();
            binding.appList.setLayoutManager(new LinearLayoutManager(this));
            binding.appList.setAdapter(adapter);

            new LoadAppsTask().execute();
        } catch (Throwable t) {
            Log.e(TAG, "AppSelectionActivity onCreate failed", t);
            showFatalErrorDialog(t);
        }
    }

    private void showFatalErrorDialog(@NonNull Throwable t) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_selection_load_failed_title)
                    .setMessage(getString(R.string.app_selection_load_failed_message,
                            describeThrowable(t)))
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .show();
        } catch (Throwable inner) {
            Log.e(TAG, "Failed to show error dialog", inner);
            finish();
        }
    }

    private void showLoadErrorDialog(@NonNull Throwable t) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_selection_load_failed_title)
                    .setMessage(getString(R.string.app_selection_load_failed_message,
                            describeThrowable(t)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Throwable inner) {
            Log.e(TAG, "Failed to show error dialog", inner);
        }
    }

    private static String describeThrowable(@NonNull Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String name = root.getClass().getSimpleName();
        return message != null && !message.isEmpty() ? name + ": " + message : name;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (target != null) {
            target.set(selected);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            }
        }
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppEntry>> {
        @Nullable private Throwable error;

        @Override
        protected List<AppEntry> doInBackground(Void... voids) {
            try {
                return loadApps();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load installed apps", t);
                error = t;
                return Collections.emptyList();
            }
        }

        private List<AppEntry> loadApps() {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> launchableApps = safeQueryIntentActivities(pm, Intent.CATEGORY_LAUNCHER);
            // Home screens / launchers usually only declare CATEGORY_HOME, so they wouldn't appear
            // in the list above — query them separately.
            List<ResolveInfo> homeApps = safeQueryIntentActivities(pm, Intent.CATEGORY_HOME);

            List<AppEntry> result = new ArrayList<>(launchableApps.size() + homeApps.size());
            String selfPackage = getPackageName();
            HashSet<String> seen = new HashSet<>();
            collectEntries(pm, launchableApps, selfPackage, seen, result);
            collectEntries(pm, homeApps, selfPackage, seen, result);

            try {
                Collator collator = Collator.getInstance(Locale.getDefault());
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
            } catch (Throwable t) {
                Log.w(TAG, "Failed to sort app list", t);
            }
            return result;
        }

        private List<ResolveInfo> safeQueryIntentActivities(PackageManager pm, String category) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(category);
                List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
                return list != null ? list : Collections.emptyList();
            } catch (Throwable t) {
                Log.w(TAG, "queryIntentActivities failed for " + category, t);
                return Collections.emptyList();
            }
        }

        private void collectEntries(PackageManager pm, List<ResolveInfo> source,
                                    String selfPackage, HashSet<String> seen,
                                    List<AppEntry> result) {
            for (ResolveInfo info : source) {
                try {
                    if (info == null || info.activityInfo == null) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || pkg.equals(selfPackage) || !seen.add(pkg)) {
                        continue;
                    }
                    String label;
                    try {
                        CharSequence raw = info.loadLabel(pm);
                        label = raw != null ? raw.toString() : pkg;
                    } catch (Throwable t) {
                        Log.w(TAG, "loadLabel failed for " + pkg, t);
                        label = pkg;
                    }
                    Drawable icon = null;
                    try {
                        icon = info.loadIcon(pm);
                    } catch (Throwable t) {
                        Log.w(TAG, "loadIcon failed for " + pkg, t);
                    }
                    result.add(new AppEntry(pkg, label, icon));
                } catch (Throwable t) {
                    Log.w(TAG, "Skipping bad ResolveInfo", t);
                }
            }
        }

        @Override
        protected void onPostExecute(List<AppEntry> result) {
            apps.clear();
            apps.addAll(result);
            adapter.notifyDataSetChanged();
            binding.appSelectionProgress.setVisibility(View.GONE);
            if (error != null) {
                showLoadErrorDialog(error);
            }
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
