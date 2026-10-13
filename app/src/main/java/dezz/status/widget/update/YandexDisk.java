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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A public Yandex.Disk folder, read through the disk's public REST API — no account, only the folder's
 * public link (its "public key"): what a path inside it holds, and a download URL for a file. Paths are the
 * folder's own, {@code "/"} being the shared folder itself. Every call blocks on the network.
 */
public final class YandexDisk {

    private static final String RESOURCES = "https://cloud-api.yandex.net/v1/disk/public/resources";
    /** The API's own ceiling is far above this; one page covers any folder of releases. */
    private static final int PAGE = 200;
    /** A listing page or a download answer is a few kilobytes; anything near this is not one. */
    private static final int MAX_ANSWER_BYTES = 1 << 20;

    private YandexDisk() {}

    /** One entry of a folder. */
    public static final class Item {
        public final String name;
        /** The entry's path in the shared folder ({@code "/v2.13.0/status-widget-v2.13.0.apk"}). */
        public final String path;
        public final boolean dir;
        /** Bytes; 0 for a folder. */
        public final long size;
        /** The disk's own SHA-256 of the file, lowercase hex, when it reports one. */
        @Nullable public final String sha256;

        public Item(String name, String path, boolean dir, long size, @Nullable String sha256) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** The path is not in the folder at all — not a failure to reach the disk. */
    public static final class NotFound extends IOException {
        NotFound(String path) {
            super("not found: " + path);
        }
    }

    /** What the folder at {@code path} holds, every page of it. */
    public static List<Item> list(String publicKey, String path) throws IOException {
        List<Item> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            String url = RESOURCES + "?public_key=" + enc(publicKey) + "&path=" + enc(path)
                    + "&limit=" + PAGE + "&offset=" + offset;
            Page page;
            try {
                page = parsePage(get(url, path));
            } catch (JSONException e) {
                throw new IOException("Yandex.Disk listing of " + path + ": " + e.getMessage());
            }
            out.addAll(page.items);
            offset += page.items.size();
            if (page.items.isEmpty() || offset >= page.total) return out;
        }
    }

    /** A URL that downloads the file at {@code path}; it is short-lived, so ask for it right before use. */
    public static String downloadUrl(String publicKey, String path) throws IOException {
        String url = RESOURCES + "/download?public_key=" + enc(publicKey) + "&path=" + enc(path);
        try {
            return new JSONObject(get(url, path)).getString("href");
        } catch (JSONException e) {
            throw new IOException("Yandex.Disk download link for " + path + ": " + e.getMessage());
        }
    }

    /** One page of a listing: its entries and how many the folder holds in all. */
    static final class Page {
        final List<Item> items;
        final int total;

        Page(List<Item> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    /** A listing answer. An answer for a FILE (no {@code _embedded}) lists nothing. */
    static Page parsePage(String json) throws JSONException {
        JSONObject embedded = new JSONObject(json).optJSONObject("_embedded");
        if (embedded == null) return new Page(Collections.emptyList(), 0);
        JSONArray array = embedded.optJSONArray("items");
        List<Item> items = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String sha = o.optString("sha256", "");
                items.add(new Item(o.getString("name"), o.getString("path"), "dir".equals(o.optString("type")),
                        o.optLong("size", 0), sha.isEmpty() ? null : sha.toLowerCase(java.util.Locale.ROOT)));
            }
        }
        return new Page(items, embedded.optInt("total", items.size()));
    }

    private static String get(String url, String path) throws IOException {
        HttpURLConnection conn = Http.open(url);
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw new NotFound(path);
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Yandex.Disk API HTTP " + code);
            return Http.readText(conn.getInputStream(), MAX_ANSWER_BYTES);
        } catch (FileNotFoundException e) {
            throw new NotFound(path);
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
