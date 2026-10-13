/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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

package dezz.status.widget.update;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A newer release the channel holds: its version, its package's place in the channel with the size and
 * digest the disk reports for it, and its release notes when the folder has them. Stored between runs as
 * JSON ({@link #toJson} / {@link #fromJson}).
 */
public final class UpdateOffer {

    public final ReleaseVersion version;
    /** The package's path in the channel's shared folder. */
    public final String apkPath;
    public final long apkSize;
    /** The disk's SHA-256 of the package, lowercase hex; null when it reported none (then size alone). */
    @Nullable public final String apkSha256;
    /** The release notes as published, or null when the folder has none. */
    @Nullable public final String notes;

    public UpdateOffer(ReleaseVersion version, String apkPath, long apkSize, @Nullable String apkSha256,
                       @Nullable String notes) {
        this.version = version;
        this.apkPath = apkPath;
        this.apkSize = apkSize;
        this.apkSha256 = apkSha256;
        this.notes = notes;
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", version.toString());
            o.put("apkPath", apkPath);
            o.put("apkSize", apkSize);
            if (apkSha256 != null) o.put("apkSha256", apkSha256);
            if (notes != null) o.put("notes", notes);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only on a non-finite number, and there is none
        }
    }

    /** The offer stored as {@code json}, or null when there is none or it no longer reads. */
    @Nullable
    static UpdateOffer fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            ReleaseVersion v = ReleaseVersion.parse(o.getString("version"));
            if (v == null) return null;
            return new UpdateOffer(v, o.getString("apkPath"), o.getLong("apkSize"),
                    o.has("apkSha256") ? o.getString("apkSha256") : null,
                    o.has("notes") ? o.getString("notes") : null);
        } catch (JSONException e) {
            return null;
        }
    }
}
