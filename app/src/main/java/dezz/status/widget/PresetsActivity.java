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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-screen management of presets: applies, saves the current configuration, renames,
 * exports, imports, and deletes user presets. Replaces the popup-menu flow that used to live in
 * {@link MainActivity}.
 */
public class PresetsActivity extends AppCompatActivity {
    private static final String TAG = "PresetsActivity";
    private static final String EXPORT_MIME_TYPE = "application/json";

    private Preferences prefs;
    private PresetsAdapter adapter;

    @Nullable
    private UserPresets.UserPreset pendingExportPreset;

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importPresetFromUri(uri);
                }
            });

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
            uri -> {
                UserPresets.UserPreset src = pendingExportPreset;
                pendingExportPreset = null;
                if (uri != null && src != null) {
                    writePresetToUri(src, uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_presets);

        prefs = new Preferences(this);

        MaterialToolbar toolbar = findViewById(R.id.presetsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.presetsList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PresetsAdapter(this, new AdapterCallback());
        list.setAdapter(adapter);

        MaterialButton saveButton = findViewById(R.id.presetsSaveCurrentButton);
        saveButton.setOnClickListener(v -> showSaveCurrentDialog());

        MaterialButton importButton = findViewById(R.id.presetsImportButton);
        importButton.setOnClickListener(v ->
                importLauncher.launch(new String[]{EXPORT_MIME_TYPE, "*/*"}));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.presetsRoot), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The list might have changed if the user came back from a rename dialog, etc.
        refreshList();
    }

    private void refreshList() {
        List<PresetsAdapter.Entry> entries = new ArrayList<>();
        for (Presets.Preset p : Presets.ALL) {
            entries.add(PresetsAdapter.Entry.bundled(this, p));
        }
        for (UserPresets.UserPreset u : UserPresets.list(this)) {
            entries.add(PresetsAdapter.Entry.user(this, u));
        }
        adapter.submit(entries);
    }

    // region — actions invoked by adapter callbacks

    private final class AdapterCallback implements PresetsAdapter.Callback {
        @Override
        public void onApply(PresetsAdapter.Entry entry) {
            confirmApply(entry);
        }

        @Override
        public void onRename(PresetsAdapter.Entry entry) {
            if (entry.user != null) showRenameDialog(entry.user);
        }

        @Override
        public void onExport(PresetsAdapter.Entry entry) {
            if (entry.user == null) return;
            pendingExportPreset = entry.user;
            exportLauncher.launch(UserPresets.exportFilename(entry.user.name));
        }

        @Override
        public void onDelete(PresetsAdapter.Entry entry) {
            if (entry.user != null) confirmDelete(entry.user);
        }
    }

    private void confirmApply(PresetsAdapter.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_apply_title)
                .setMessage(getString(R.string.preset_apply_message, entry.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preset_apply_confirm, (d, w) -> applyEntry(entry))
                .show();
    }

    private void applyEntry(PresetsAdapter.Entry entry) {
        String json;
        try {
            if (entry.bundled != null) {
                json = Presets.readJson(this, entry.bundled);
            } else if (entry.user != null) {
                json = UserPresets.read(entry.user.file);
            } else {
                return;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read preset " + entry.name, e);
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            prefs.importFromJson(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply preset " + entry.name, e);
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // Same restart pattern MainActivity itself uses for reset / preset application — start
        // a fresh MainActivity with CLEAR_TASK so view-state restoration can't write the old
        // values back through listeners attached during initializeViews().
        if (WidgetService.isRunning()) {
            stopService(new Intent(this, WidgetService.class));
        }
        if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startForegroundService(new Intent(this, WidgetService.class));
        }
        Toast.makeText(this, getString(R.string.preset_applied_toast, entry.name),
                Toast.LENGTH_SHORT).show();
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
        finish();
    }

    private void confirmDelete(UserPresets.UserPreset preset) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_delete_title)
                .setMessage(getString(R.string.preset_delete_message, preset.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preset_delete_confirm, (d, w) -> {
                    if (UserPresets.delete(preset)) {
                        Toast.makeText(this,
                                getString(R.string.preset_deleted_toast, preset.name),
                                Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else {
                        Toast.makeText(this, R.string.preset_delete_failed,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void showRenameDialog(UserPresets.UserPreset preset) {
        showNameDialog(R.string.preset_rename_title, R.string.preset_rename_confirm, preset.name,
                newName -> {
                    UserPresets.RenameResult result;
                    try {
                        result = UserPresets.rename(this, preset, newName);
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to rename preset", e);
                        Toast.makeText(this, R.string.preset_rename_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!result.success) {
                        if (result.collision) {
                            Toast.makeText(this, R.string.preset_rename_collision,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, R.string.preset_rename_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    Toast.makeText(this, getString(R.string.preset_renamed_toast, newName),
                            Toast.LENGTH_SHORT).show();
                    refreshList();
                });
    }

    private void showSaveCurrentDialog() {
        showNameDialog(R.string.preset_save_title, R.string.preset_save_confirm, "",
                name -> {
                    String json;
                    try {
                        json = prefs.exportToJson(name);
                    } catch (org.json.JSONException e) {
                        Log.e(TAG, "Failed to serialize current prefs", e);
                        Toast.makeText(this, R.string.preset_save_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    persistUserPreset(name, json);
                });
    }

    private void importPresetFromUri(Uri uri) {
        String json;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open input stream");
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            json = builder.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read preset", e);
            Toast.makeText(this, R.string.preset_import_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (!json.contains("\"dezz.status.widget.settings\"")) {
            Toast.makeText(this, R.string.import_invalid_file_toast, Toast.LENGTH_LONG).show();
            return;
        }
        String suggested = Preferences.readPresetName(json);
        if (suggested == null) suggested = "";
        final String jsonFinal = json;
        showNameDialog(R.string.preset_import_title, R.string.preset_save_confirm, suggested,
                name -> {
                    String rewritten = jsonFinal;
                    try {
                        JSONObject root = new JSONObject(jsonFinal);
                        root.put("presetName", name);
                        rewritten = root.toString(2);
                    } catch (org.json.JSONException ignored) {
                    }
                    persistUserPreset(name, rewritten);
                });
    }

    private void persistUserPreset(String displayName, String json) {
        String slug = UserPresets.sanitiseFilename(displayName);
        if (slug.isEmpty()) {
            Toast.makeText(this, R.string.preset_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        File existing = new File(new File(getFilesDir(), "user_presets"), slug + ".json");
        if (existing.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.preset_overwrite_title)
                    .setMessage(getString(R.string.preset_overwrite_message, displayName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preset_overwrite_confirm,
                            (d, w) -> writeUserPresetFile(displayName, json))
                    .show();
        } else {
            writeUserPresetFile(displayName, json);
        }
    }

    private void writeUserPresetFile(String displayName, String json) {
        try {
            UserPresets.save(this, displayName, json);
            Toast.makeText(this, getString(R.string.preset_saved_toast, displayName),
                    Toast.LENGTH_SHORT).show();
            refreshList();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write user preset " + displayName, e);
            Toast.makeText(this, R.string.preset_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void writePresetToUri(UserPresets.UserPreset preset, Uri uri) {
        try (InputStream in = new java.io.FileInputStream(preset.file);
             java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("Could not open output stream");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to export user preset " + preset.name, e);
            Toast.makeText(this, R.string.preset_export_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.preset_exported_toast, preset.name),
                Toast.LENGTH_SHORT).show();
    }

    // endregion

    private interface NameCallback {
        void onName(String name);
    }

    /** Reusable text-input modal — same UX as the legacy preset name dialog. */
    private void showNameDialog(int titleRes, int confirmRes, String initialValue,
                                NameCallback onOk) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.preset_name_hint);
        input.setText(initialValue);
        input.setSelection(input.getText().length());
        int pad = getResources().getDimensionPixelSize(R.dimen.optionsMargin);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        frame.addView(input, lp);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(confirmRes, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.preset_name_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    onOk.onName(name);
                })
                .show();
    }
}
