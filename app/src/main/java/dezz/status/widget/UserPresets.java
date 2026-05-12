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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * User-saved presets, stored as JSON files under {@code filesDir/user_presets/}. Each file uses the
 * same schema as {@link Preferences#exportToJson()} with an additional top-level {@code presetName}
 * field carrying the human-readable name. The filename is a sanitised version of that name —
 * keeping the on-disk name predictable for export-by-filename UX while letting the display name
 * survive a filesystem rename.
 */
public final class UserPresets {
    private static final String DIR = "user_presets";
    private static final String EXT = ".json";

    public static final class UserPreset {
        public final String name;
        public final File file;

        UserPreset(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    @NonNull
    private static File dir(@NonNull Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    @NonNull
    public static List<UserPreset> list(@NonNull Context context) {
        File[] files = dir(context).listFiles((f, name) -> name.toLowerCase().endsWith(EXT));
        List<UserPreset> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            String name = readDisplayName(f);
            if (name == null) {
                name = stripExt(f.getName());
            }
            out.add(new UserPreset(name, f));
        }
        Collections.sort(out, Comparator.comparing(p -> p.name.toLowerCase()));
        return out;
    }

    /**
     * Writes {@code json} (which must already include a {@code presetName} field) to a file whose
     * name is derived from {@code displayName}. Returns the created file. Overwrites any existing
     * file with the same sanitised name.
     */
    @NonNull
    public static File save(@NonNull Context context, @NonNull String displayName,
                            @NonNull String json) throws IOException {
        String base = sanitiseFilename(displayName);
        if (base.isEmpty()) {
            throw new IOException("Invalid preset name");
        }
        File target = new File(dir(context), base + EXT);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }

    @NonNull
    public static String read(@NonNull File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static boolean delete(@NonNull UserPreset preset) {
        return preset.file.delete();
    }

    /**
     * Filename slug: keeps Unicode letters and digits (so Cyrillic names survive), collapses
     * whitespace to underscores, and strips characters that are filesystem-reserved on the kinds
     * of storage the export goes through (FAT-flavoured SD cards, MTP shares). Falls back to
     * empty if the input contains no letters or digits at all.
     */
    @NonNull
    public static String sanitiseFilename(@NonNull String displayName) {
        String s = displayName.trim();
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '-' || cp == '_') {
                out.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                    out.append('_');
                }
            }
            // Other characters — including FS-reserved / \ : * ? " < > | and punctuation — dropped.
            i += width;
        }
        // Trim trailing underscores so " 1" → "1" rather than "_1".
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Suggested filename for sharing/exporting outside the app. Includes the {@code preset-} prefix
     * so the receiver recognises it as a preset, not a full settings export.
     */
    @NonNull
    public static String exportFilename(@NonNull String displayName) {
        String slug = sanitiseFilename(displayName);
        if (slug.isEmpty()) {
            slug = "preset";
        }
        return "status-widget-preset-" + slug + EXT;
    }

    @Nullable
    private static String readDisplayName(@NonNull File file) {
        try {
            String json = read(file);
            JSONObject root = new JSONObject(json);
            String name = root.optString("presetName", "").trim();
            return name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    private static String stripExt(@NonNull String name) {
        if (name.toLowerCase().endsWith(EXT)) {
            return name.substring(0, name.length() - EXT.length());
        }
        return name;
    }

    private UserPresets() {}
}
