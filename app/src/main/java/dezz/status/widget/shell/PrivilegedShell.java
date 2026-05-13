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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Permissions;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Out-of-band privileged shell channel used to grant the app system-level permissions on
 * car head units where the standard Settings UI for Usage Access / Notification Listener
 * doesn't exist (e.g. Geely Monjaro / QUALCOMM KX11 stack).
 *
 * <p>Strategy: probe a small fixed set of well-known network ports on every local interface
 * (loopback + every {@code up} non-loopback IPv4/IPv6) until one responds to ADB or Telnet,
 * then run shell commands through that transport with the elevated capabilities that come
 * from {@code shell}-uid command execution. Same trick as the sibling {@code stealth} app,
 * but stripped of port-scan diagnostics — we only care about getting one working channel.
 *
 * <p>The found endpoint is cached in device-protected SharedPreferences so subsequent app
 * launches verify it directly without enumerating interfaces.
 *
 * <p>Probe order on each host: ADB 5555 → ADB 7777 → Telnet 23. ADB first because the probe
 * is allocation-safe (manual handshake — see {@link AdbTransport#probe}) and Telnet listeners
 * tend to advertise themselves loudly with banners.
 *
 * <p>All public methods are thread-safe. Discovery and command execution share a single-thread
 * executor so callers never race a half-completed discovery; user-facing callbacks are
 * delivered on the main thread.
 */
public class PrivilegedShell {
    private static final String TAG = "PrivilegedShell";

    /** Concurrency cap for parallel probes during discovery. */
    private static final int PROBE_PARALLELISM = 8;

    private static final int[] ADB_PORTS = {5555, 7777};
    private static final int[] TELNET_PORTS = {23};

    /** Read-timeout used by the Telnet sanity probe (full {@link TelnetTransport#exec}). */
    private static final int TELNET_PROBE_READ_TIMEOUT_MS = 1500;

    /**
     * After a discovery returns null we mute further attempts for this long. Without it,
     * every {@code onCreate} of MainActivity (rotations, navigations) would re-run the full
     * 10+ second scan on devices where no privileged channel exists. 60 s is short enough
     * that a user reopening the app a minute later will get a retry.
     */
    private static final long FAILED_DISCOVERY_TTL_MS = 60_000L;

    private static volatile PrivilegedShell instance;

    public static PrivilegedShell get(Context context) {
        PrivilegedShell local = instance;
        if (local == null) {
            synchronized (PrivilegedShell.class) {
                local = instance;
                if (local == null) {
                    local = new PrivilegedShell(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context appContext;
    private final ConnectionStorage storage;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Last endpoint that gave us a working shell — kept hot between calls. */
    private final AtomicReference<ConnectionStorage.Endpoint> activeEndpoint = new AtomicReference<>();

    /** Timestamp of the last discovery that returned null. 0 means "no recent failure". */
    private volatile long lastDiscoveryFailureMillis = 0L;

    /**
     * Serialises discovery and command runs so two concurrent grant requests can't race
     * each other over the same socket and so a discovery in progress finishes before any
     * dependent {@code runCommand} starts.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreads("priv-shell"));

    private PrivilegedShell(Context appContext) {
        this.appContext = appContext;
        this.storage = new ConnectionStorage(appContext);
        ConnectionStorage.Endpoint cached = storage.load();
        if (cached != null) {
            activeEndpoint.set(cached);
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Permission categories the privileged shell can grant. Used in {@link GrantResult} so
     * the UI layer can localise its own labels — the shell module stays string-resource free.
     */
    public enum PermissionKind {
        OVERLAY,
        FOREGROUND_LOCATION,
        BACKGROUND_LOCATION,
        USAGE_ACCESS,
        NOTIFICATION,
    }

    /** Result of an {@link #ensurePrivileges} call. */
    public static class GrantResult {
        /**
         * True when we connected through a privileged channel. {@code grantedKinds} +
         * {@code failedKinds} together describe what we tried; both being empty means
         * everything requested was already granted on entry.
         */
        public final boolean transportAvailable;
        /** Human-readable description of the transport, or null if discovery failed. */
        @Nullable public final String transportDescription;
        /** Permissions newly granted by this call. */
        @NonNull public final List<PermissionKind> grantedKinds;
        /** Permissions we tried to grant but couldn't verify afterwards. */
        @NonNull public final List<PermissionKind> failedKinds;

        GrantResult(boolean transportAvailable,
                    @Nullable String transportDescription,
                    @NonNull List<PermissionKind> grantedKinds,
                    @NonNull List<PermissionKind> failedKinds) {
            this.transportAvailable = transportAvailable;
            this.transportDescription = transportDescription;
            this.grantedKinds = grantedKinds;
            this.failedKinds = failedKinds;
        }

        public boolean anyGranted() { return !grantedKinds.isEmpty(); }
        public boolean anyFailed() { return !failedKinds.isEmpty(); }
    }

    public interface GrantCallback {
        /** Always delivered on the main thread. */
        void onResult(GrantResult result);
    }

    public interface CommandCallback {
        /** Delivered on the main thread. {@code output} is null on transport failure. */
        void onResult(@Nullable String output, @Nullable String error);
    }

    /** Request for {@link #ensurePrivileges}: which permissions the caller wants verified/granted. */
    public static class Request {
        final String packageName;
        final boolean overlay;
        final boolean foregroundLocation;
        final boolean backgroundLocation;
        final boolean usageAccess;
        final boolean notificationListener;
        @Nullable final String notificationListenerComponent;

        private Request(Builder b) {
            this.packageName = b.packageName;
            this.overlay = b.overlay;
            this.foregroundLocation = b.foregroundLocation;
            this.backgroundLocation = b.backgroundLocation;
            this.usageAccess = b.usageAccess;
            this.notificationListener = b.notificationListener;
            this.notificationListenerComponent = b.notificationListenerComponent;
        }

        boolean nothingToDo() {
            return !overlay && !foregroundLocation && !backgroundLocation
                    && !usageAccess && !notificationListener;
        }

        public static Builder forPackage(String packageName) { return new Builder(packageName); }

        public static class Builder {
            private final String packageName;
            private boolean overlay = false;
            private boolean foregroundLocation = false;
            private boolean backgroundLocation = false;
            private boolean usageAccess = false;
            private boolean notificationListener = false;
            @Nullable private String notificationListenerComponent = null;

            Builder(String packageName) { this.packageName = packageName; }

            public Builder withOverlay() { this.overlay = true; return this; }
            public Builder withForegroundLocation() { this.foregroundLocation = true; return this; }
            public Builder withBackgroundLocation() { this.backgroundLocation = true; return this; }
            public Builder withUsageAccess() { this.usageAccess = true; return this; }
            public Builder withNotificationListener(String component) {
                this.notificationListener = true;
                this.notificationListenerComponent = component;
                return this;
            }
            public Request build() { return new Request(this); }
        }
    }

    /**
     * Locate a working privileged transport (cache → fresh scan) and use it to grant any
     * of the requested permissions that aren't already on. Callback runs on the main thread.
     */
    public void ensurePrivileges(Request request, GrantCallback callback) {
        executor.execute(() -> {
            try {
                GrantResult result = doEnsurePrivileges(request);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Throwable t) {
                Log.e(TAG, "ensurePrivileges crashed", t);
                List<PermissionKind> empty = new ArrayList<>();
                GrantResult fallback = new GrantResult(false, null, empty, empty);
                mainHandler.post(() -> callback.onResult(fallback));
            }
        });
    }

    /** Run an arbitrary shell command through the active transport (rediscovering if needed). */
    public void runCommand(String command, CommandCallback callback) {
        executor.execute(() -> {
            ConnectionStorage.Endpoint endpoint = ensureEndpoint();
            if (endpoint == null) {
                mainHandler.post(() -> callback.onResult(null, "No privileged transport available"));
                return;
            }
            ShellTransport transport = null;
            try {
                transport = open(endpoint);
                String output = transport.exec(command);
                mainHandler.post(() -> callback.onResult(output, null));
            } catch (Exception e) {
                Log.w(TAG, "runCommand failed", e);
                // Endpoint may have died (head unit reboot, port reshuffle). Invalidate so
                // the next call re-discovers.
                activeEndpoint.set(null);
                storage.clear();
                mainHandler.post(() -> callback.onResult(null, e.getMessage()));
            } finally {
                if (transport != null) transport.close();
            }
        });
    }

    /** True if we already know about a working channel — caller can show "advanced" UI. */
    public boolean hasWorkingTransport() {
        return activeEndpoint.get() != null;
    }

    // ── Discovery ─────────────────────────────────────────────────────

    /**
     * Return a known-working endpoint, re-discovering if the cached one no longer answers.
     * Runs on the {@link #executor} thread; do not call directly from the UI.
     */
    @Nullable
    private ConnectionStorage.Endpoint ensureEndpoint() {
        ConnectionStorage.Endpoint active = activeEndpoint.get();
        if (active != null && quickProbe(active)) {
            return active;
        }
        activeEndpoint.set(null);

        // Skip the full scan if the previous one failed recently — see FAILED_DISCOVERY_TTL_MS.
        long failedAgo = System.currentTimeMillis() - lastDiscoveryFailureMillis;
        if (lastDiscoveryFailureMillis > 0 && failedAgo < FAILED_DISCOVERY_TTL_MS) {
            Log.d(TAG, "Skipping discovery — previous attempt failed " + failedAgo + " ms ago");
            return null;
        }

        ConnectionStorage.Endpoint discovered = discover();
        if (discovered != null) {
            activeEndpoint.set(discovered);
            storage.save(discovered);
            lastDiscoveryFailureMillis = 0L;
        } else {
            storage.clear();
            lastDiscoveryFailureMillis = System.currentTimeMillis();
        }
        return discovered;
    }

    /**
     * Verify a previously-working endpoint with the cheapest possible test for its transport.
     * Used on fast path so app startup isn't paying for a fresh scan every time.
     */
    private boolean quickProbe(ConnectionStorage.Endpoint endpoint) {
        try {
            if (ConnectionStorage.TRANSPORT_ADB.equals(endpoint.transport)) {
                return AdbTransport.probe(endpoint.host, endpoint.port);
            }
            // Telnet has no equivalent allocation-safe probe — just connect and close.
            ShellTransport t = TelnetTransport.connect(endpoint.host, endpoint.port);
            t.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private ConnectionStorage.Endpoint discover() {
        long started = System.currentTimeMillis();
        List<String> hosts = candidateHosts();
        Log.i(TAG, "Discovery start: " + hosts.size() + " hosts");

        int probeCount = hosts.size() * (ADB_PORTS.length + TELNET_PORTS.length);
        if (probeCount == 0) {
            // Defensive — candidateHosts() always adds the two loopbacks, but keep this
            // guard in case future refactoring removes the hardcoded entries.
            Log.w(TAG, "Discovery: no candidate hosts");
            return null;
        }

        // ConcurrentHashMap.newKeySet so probe threads can register their sockets without
        // contention. On first success we close every still-registered socket — this is the
        // only way to unblock probe threads stuck in Socket.connect/read (Thread.interrupt
        // doesn't reach them). Sockets self-deregister in their own finally blocks too,
        // so the set never grows unbounded across consecutive discoveries.
        Set<Closeable> liveSockets = ConcurrentHashMap.newKeySet();

        ExecutorService probePool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(PROBE_PARALLELISM, probeCount)),
                daemonThreads("priv-probe"));
        ExecutorCompletionService<ConnectionStorage.Endpoint> cs =
                new ExecutorCompletionService<>(probePool);

        int submitted = 0;
        try {
            for (String host : hosts) {
                for (int port : ADB_PORTS) {
                    cs.submit(() -> probeAdb(host, port, liveSockets));
                    submitted++;
                }
                for (int port : TELNET_PORTS) {
                    cs.submit(() -> probeTelnet(host, port, liveSockets));
                    submitted++;
                }
            }
            for (int i = 0; i < submitted; i++) {
                try {
                    Future<ConnectionStorage.Endpoint> f = cs.take();
                    ConnectionStorage.Endpoint e = f.get();
                    if (e != null) {
                        Log.i(TAG, "Discovery found " + e.transport + " on "
                                + ShellTransport.formatHostPort(e.host, e.port)
                                + " (" + (System.currentTimeMillis() - started) + " ms)");
                        return e;
                    }
                } catch (Exception ignored) {
                    // Probe failed — try the next one.
                }
            }
            Log.w(TAG, "Discovery: no working endpoint after "
                    + (System.currentTimeMillis() - started) + " ms");
            return null;
        } finally {
            // Force-close any sockets that probe threads are still blocked on so they wake
            // up immediately with a SocketException instead of dawdling for the connect /
            // read timeout while we've already returned.
            for (Closeable c : liveSockets) {
                try { c.close(); } catch (Exception ignored) {}
            }
            probePool.shutdownNow();
        }
    }

    @Nullable
    private static ConnectionStorage.Endpoint probeAdb(String host, int port,
                                                       Set<Closeable> liveSockets) {
        Socket[] socketHolder = new Socket[1];
        try {
            boolean ok = AdbTransport.probe(host, port, s -> {
                socketHolder[0] = s;
                liveSockets.add(s);
            });
            return ok
                    ? new ConnectionStorage.Endpoint(host, port, ConnectionStorage.TRANSPORT_ADB)
                    : null;
        } finally {
            if (socketHolder[0] != null) liveSockets.remove(socketHolder[0]);
        }
    }

    @Nullable
    private static ConnectionStorage.Endpoint probeTelnet(String host, int port,
                                                          Set<Closeable> liveSockets) {
        Socket[] socketHolder = new Socket[1];
        ShellTransport t = null;
        try {
            t = TelnetTransport.connect(host, port, s -> {
                socketHolder[0] = s;
                liveSockets.add(s);
            });
            // Canonical sanity probe — same one stealth uses. Any Android shell will answer
            // with the path to framework-res.apk. Use the short read-timeout overload so
            // an unresponsive shell doesn't burn the full 5-second default.
            String resp = ((TelnetTransport) t).exec("pm path android",
                    TELNET_PROBE_READ_TIMEOUT_MS);
            if (resp != null && resp.contains("package:")) {
                return new ConnectionStorage.Endpoint(host, port, ConnectionStorage.TRANSPORT_TELNET);
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (t != null) t.close();
            if (socketHolder[0] != null) liveSockets.remove(socketHolder[0]);
        }
    }

    /**
     * Enumerate every interface address worth probing. Loopback (both v4 and v6) always
     * first because internal listeners are most common — ADB on Geely binds to 127.0.0.1.
     */
    private static List<String> candidateHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        hosts.add("::1");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        // Keep both IPv4 and IPv6 — some head units only expose ADB on v6,
                        // and on a few Cityray builds users could only reach it via IPv6.
                        hosts.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to enumerate network interfaces: " + e.getMessage());
        }
        return new ArrayList<>(hosts);
    }

    private ShellTransport open(ConnectionStorage.Endpoint endpoint) throws Exception {
        if (ConnectionStorage.TRANSPORT_ADB.equals(endpoint.transport)) {
            return AdbTransport.connect(appContext, endpoint.host, endpoint.port);
        }
        return TelnetTransport.connect(endpoint.host, endpoint.port);
    }

    // ── Permission grant logic ───────────────────────────────────────

    private GrantResult doEnsurePrivileges(Request request) {
        if (request.nothingToDo()) {
            return new GrantResult(true, "(no-op)", new ArrayList<>(), new ArrayList<>());
        }

        ConnectionStorage.Endpoint endpoint = ensureEndpoint();
        if (endpoint == null) {
            return new GrantResult(false, null, new ArrayList<>(), new ArrayList<>());
        }
        String description = ShellTransport.formatHostPort(endpoint.host, endpoint.port)
                + " (" + endpoint.transport + ")";

        List<PermissionKind> granted = new ArrayList<>();
        List<PermissionKind> failed = new ArrayList<>();

        ShellTransport transport = null;
        try {
            transport = open(endpoint);
            String pkg = request.packageName;

            // Order matters: runtime location permissions must be granted in foreground →
            // background order on Android 11+, otherwise `pm grant ACCESS_BACKGROUND_LOCATION`
            // fails with "has not requested permission ACCESS_BACKGROUND_LOCATION".
            if (request.overlay) {
                applyPermission(transport,
                        new String[]{"appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"},
                        () -> Permissions.checkOverlayPermission(appContext),
                        PermissionKind.OVERLAY,
                        granted, failed);
            }
            if (request.foregroundLocation) {
                applyPermission(transport,
                        new String[]{
                                "pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION",
                                "pm grant " + pkg + " android.permission.ACCESS_COARSE_LOCATION"
                        },
                        () -> Permissions.checkForMissingForegroundPermissions(appContext).isEmpty(),
                        PermissionKind.FOREGROUND_LOCATION,
                        granted, failed);
            }
            if (request.backgroundLocation) {
                applyPermission(transport,
                        new String[]{"pm grant " + pkg
                                + " android.permission.ACCESS_BACKGROUND_LOCATION"},
                        () -> Permissions.isBackgroundLocationGranted(appContext),
                        PermissionKind.BACKGROUND_LOCATION,
                        granted, failed);
            }
            if (request.usageAccess) {
                applyPermission(transport,
                        new String[]{"appops set " + pkg + " GET_USAGE_STATS allow"},
                        () -> Permissions.isUsageAccessGranted(appContext),
                        PermissionKind.USAGE_ACCESS,
                        granted, failed);
            }
            if (request.notificationListener && request.notificationListenerComponent != null) {
                applyPermission(transport,
                        new String[]{"cmd notification allow_listener "
                                + request.notificationListenerComponent},
                        () -> Permissions.isNotificationAccessGranted(appContext),
                        PermissionKind.NOTIFICATION,
                        granted, failed);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to open transport for grant flow", e);
            // Caller treats transportAvailable=false as "fall back to the manual route".
            activeEndpoint.set(null);
            storage.clear();
            return new GrantResult(false, null, granted, failed);
        } finally {
            if (transport != null) transport.close();
        }

        return new GrantResult(true, description, granted, failed);
    }

    /**
     * Run grant commands and verify the underlying permission flipped. If it was already
     * granted on entry, do nothing — we don't want to spam the shell with no-op commands or
     * advertise a "newly granted" toast for state that was already fine. Multi-command
     * inputs are run in order, treating any single failure as failure of the whole label
     * (matches the user-visible grouping: "Location" is FINE + COARSE together).
     */
    private void applyPermission(ShellTransport transport,
                                 String[] commands,
                                 PermissionCheck check,
                                 PermissionKind kind,
                                 List<PermissionKind> granted,
                                 List<PermissionKind> failed) {
        if (check.isGranted()) {
            return; // already on — nothing to do
        }
        for (String command : commands) {
            try {
                String output = transport.exec(command);
                Log.i(TAG, "Granted " + kind + " step via shell. Command: " + command
                        + " | output: " + (output == null ? "" : output.trim()));
            } catch (Exception e) {
                Log.w(TAG, "Grant command failed for " + kind + " (" + command + ")", e);
                failed.add(kind);
                return;
            }
        }
        // Re-check via Android's own API — `appops set` and `cmd notification` are async on
        // some builds; a small recheck loop handles the race.
        for (int i = 0; i < 5; i++) {
            if (check.isGranted()) {
                granted.add(kind);
                return;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (check.isGranted()) {
            granted.add(kind);
        } else {
            failed.add(kind);
        }
    }

    @FunctionalInterface
    private interface PermissionCheck {
        boolean isGranted();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static ThreadFactory daemonThreads(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Convenience: format the {@code <pkg>/<service-class>} component for the
     * {@code cmd notification allow_listener} argument.
     */
    public static String notificationListenerComponent(String packageName, Class<?> serviceClass) {
        return packageName + "/" + serviceClass.getName();
    }
}
