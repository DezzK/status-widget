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
import androidx.annotation.StringRes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Catalogue of bundled configuration presets. Each preset is shipped as a JSON file under
 * {@code assets/presets/} using the same schema as the user's export — so applying a preset is
 * just an in-process call to {@link Preferences#importFromJson(String)} on bundled JSON.
 */
public final class Presets {
    public static final class Preset {
        public final String id;
        @StringRes
        public final int nameRes;
        @StringRes
        public final int descRes;
        public final String assetFile;

        Preset(String id, @StringRes int nameRes, @StringRes int descRes, String assetFile) {
            this.id = id;
            this.nameRes = nameRes;
            this.descRes = descRes;
            this.assetFile = assetFile;
        }
    }

    public static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
            new Preset("minimal", R.string.preset_minimal_name, R.string.preset_minimal_desc,
                    "presets/minimal.json"),
            new Preset("standard", R.string.preset_standard_name, R.string.preset_standard_desc,
                    "presets/standard.json"),
            new Preset("status_bar", R.string.preset_status_bar_name, R.string.preset_status_bar_desc,
                    "presets/status_bar.json"),
            new Preset("sunlight", R.string.preset_sunlight_name, R.string.preset_sunlight_desc,
                    "presets/sunlight.json"),
            new Preset("retro", R.string.preset_retro_name, R.string.preset_retro_desc,
                    "presets/retro.json")
    ));

    @NonNull
    public static String readJson(@NonNull Context context, @NonNull Preset preset)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = context.getAssets().open(preset.assetFile);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private Presets() {}
}
