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

import android.os.Handler;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronously probes a "whitelist" host (Yandex) and a global host (Google) over TCP to
 * classify the current Internet reachability:
 * <ul>
 *   <li>{@link Reach#FULL} — both reachable, the network has unrestricted Internet access;</li>
 *   <li>{@link Reach#WHITELIST} — only Yandex is reachable, indicates a captive / restricted network
 *       that whitelists certain Russian services;</li>
 *   <li>{@link Reach#NONE} — neither is reachable, no usable Internet.</li>
 * </ul>
 */
public class ReachabilityChecker {
    private static final String TAG = "ReachabilityChecker";
    private static final String WHITELIST_HOST = "ya.ru";
    private static final String GLOBAL_HOST = "www.google.com";
    private static final int PROBE_PORT = 443;
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    public enum Reach {
        NONE,
        WHITELIST,
        FULL
    }

    public interface Callback {
        void onResult(Reach reach);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public ReachabilityChecker(Handler mainHandler) {
        this.mainHandler = mainHandler;
    }

    public void check(Callback callback) {
        if (shutdown.get()) return;
        executor.submit(() -> {
            boolean google = isReachable(GLOBAL_HOST);
            boolean yandex = google || isReachable(WHITELIST_HOST);
            Reach result;
            if (google) {
                result = Reach.FULL;
            } else if (yandex) {
                result = Reach.WHITELIST;
            } else {
                result = Reach.NONE;
            }
            Log.d(TAG, "reachability: google=" + google + " yandex=" + yandex + " → " + result);
            if (!shutdown.get()) {
                mainHandler.post(() -> callback.onResult(result));
            }
        });
    }

    private boolean isReachable(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, PROBE_PORT), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void shutdown() {
        shutdown.set(true);
        executor.shutdownNow();
    }
}
