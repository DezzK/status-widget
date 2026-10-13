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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * How the app talks HTTP to the update channel: one connection setup, one bound on reading a text body. Every call blocks — callers are executors whose only
 * job is to block, never a thread that serves anything.
 */
public final class Http {

    /** A connect that has not completed by now is a network that is not there. */
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    /** Silence between two reads of a body: a stalled transfer, not a slow one. */
    public static final int READ_TIMEOUT_MS = 30_000;

    private Http() {}

    /** A GET connection to {@code url}, following redirects (Yandex.Disk answers a download with one). */
    public static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    /**
     * {@code in} as UTF-8 text, refusing a body longer than {@code maxBytes} — a text a peer sends is held in
     * memory whole, so its size is ours to bound, not the peer's. A leading byte-order mark is dropped.
     */
    public static String readText(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bos.size() + n > maxBytes) throw new IOException("body over " + maxBytes + " bytes");
            bos.write(buf, 0, n);
        }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
