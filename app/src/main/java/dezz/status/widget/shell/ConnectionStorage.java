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

package dezz.status.widget.shell;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Persists the last working {@link PrivilegedShell} endpoint so subsequent app launches can
 * verify it first instead of re-scanning every interface from scratch. One endpoint is
 * enough — we don't show diagnostics like {@code stealth} does, just need the fast path.
 */
public class ConnectionStorage {
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_TRANSPORT = "transport";

    public static final String TRANSPORT_ADB = "ADB";
    public static final String TRANSPORT_TELNET = "Telnet";

    private final SharedPreferences prefs;

    public ConnectionStorage(Context context) {
        // Device-protected storage so we can run the privileged shell pre-unlock if the
        // widget service ever needs it. Matches the other prefs files in this app.
        Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(
                context.getPackageName() + "_privileged_shell", Context.MODE_PRIVATE);
    }

    public static class Endpoint {
        public final String host;
        public final int port;
        public final String transport;

        public Endpoint(String host, int port, String transport) {
            this.host = host;
            this.port = port;
            this.transport = transport;
        }
    }

    @Nullable
    public Endpoint load() {
        String host = prefs.getString(KEY_HOST, null);
        int port = prefs.getInt(KEY_PORT, -1);
        String transport = prefs.getString(KEY_TRANSPORT, null);
        if (host == null || port <= 0 || transport == null) return null;
        return new Endpoint(host, port, transport);
    }

    public void save(Endpoint endpoint) {
        prefs.edit()
                .putString(KEY_HOST, endpoint.host)
                .putInt(KEY_PORT, endpoint.port)
                .putString(KEY_TRANSPORT, endpoint.transport)
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
