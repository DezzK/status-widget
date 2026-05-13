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

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Shell transport over Telnet protocol (raw TCP with IAC negotiation).
 * <p>
 * Reliably handles both echo-on and echo-off Telnet servers by using
 * {@code lastIndexOf} on the end-of-command marker, draining any trailing
 * prompt with a short timeout, and stripping the echoed command line when
 * detected. Ported verbatim from the stealth project — same head units use
 * the same listeners.
 */
public class TelnetTransport implements ShellTransport {
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int BANNER_DRAIN_MS = 500;
    private static final int TRAILING_DRAIN_MS = 200;
    private static final String END_MARKER = "__DONE__";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String host;
    private final int port;

    private TelnetTransport(Socket socket, InputStream in, OutputStream out, String host, int port) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.host = host;
        this.port = port;
    }

    /** Connect via Telnet, handle initial IAC negotiation and drain the banner. */
    public static TelnetTransport connect(String host, int port) throws Exception {
        return connect(host, port, null);
    }

    /**
     * Variant of {@link #connect(String, int)} that publishes the underlying {@link Socket}
     * to {@code socketSink} as soon as it's constructed — letting an external coordinator
     * (e.g. {@code PrivilegedShell} during parallel discovery) close the socket and unblock
     * the connect/read calls when a sibling probe has already won.
     */
    public static TelnetTransport connect(String host, int port,
                                          @Nullable Consumer<Socket> socketSink) throws Exception {
        Socket socket = new Socket();
        if (socketSink != null) {
            socketSink.accept(socket);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            drainBanner(socket, in, out);

            return new TelnetTransport(socket, in, out, host, port);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public String describe() {
        return "telnet " + ShellTransport.formatHostPort(host, port);
    }

    @Override
    public String exec(String command) throws Exception {
        return exec(command, READ_TIMEOUT_MS);
    }

    /**
     * Variant of {@link #exec(String)} with caller-controlled read timeout. Useful in probe
     * paths where {@link #READ_TIMEOUT_MS}'s 5-second default would compound across many
     * candidate hosts/ports during discovery.
     * <p>
     * Caveat: {@code command} is sent verbatim — no quoting or shell-escaping. Don't pass
     * untrusted input.
     */
    public String exec(String command, int readTimeoutMs) throws Exception {
        socket.setSoTimeout(readTimeoutMs);

        String fullCommand = command + " ; echo " + END_MARKER + "\n";
        out.write(fullCommand.getBytes(StandardCharsets.UTF_8));
        out.flush();

        return readResponseUntilMarker(readTimeoutMs);
    }

    @Override
    public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ── Banner drain ──────────────────────────────────────────────────

    /**
     * Drain initial Telnet banner and IAC negotiation. Reads until {@value BANNER_DRAIN_MS}ms
     * of silence — more robust than a fixed sleep on slow servers.
     */
    private static void drainBanner(Socket socket, InputStream in, OutputStream out) throws Exception {
        socket.setSoTimeout(BANNER_DRAIN_MS);
        byte[] buf = new byte[4096];
        while (true) {
            int len;
            try {
                len = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (len == -1) break;
            handleIacInBuffer(buf, len, out);
            // Banner text is discarded
        }
    }

    /**
     * Walk a freshly-read buffer, replying to any IAC WILL/DO with WONT/DONT.
     * Banner content (non-IAC bytes) is ignored.
     */
    private static void handleIacInBuffer(byte[] buf, int len, OutputStream out) throws Exception {
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            if (b == 0xFF && i + 2 < len) {
                int cmd = buf[i + 1] & 0xFF;
                int opt = buf[i + 2] & 0xFF;
                if (cmd == 0xFB || cmd == 0xFD) {
                    out.write(new byte[]{(byte) 0xFF, (byte) (cmd == 0xFB ? 0xFC : 0xFE), (byte) opt});
                    out.flush();
                }
                i += 2;
            }
        }
    }

    // ── Response reading ──────────────────────────────────────────────

    private String readResponseUntilMarker(int readTimeoutMs) throws Exception {
        // Cap the post-marker trailing-drain window at the caller-supplied read timeout —
        // otherwise a short probe timeout could be undermined by a fixed-200ms trailing wait.
        int trailingDrainMs = Math.min(TRAILING_DRAIN_MS, Math.max(50, readTimeoutMs));
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        byte[] markerBytes = END_MARKER.getBytes(StandardCharsets.US_ASCII);
        boolean markerSeen = false;

        while (true) {
            int len;
            try {
                len = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (len == -1) break;

            for (int i = 0; i < len; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0xFF) {
                    if (i + 2 < len) {
                        int cmd = buf[i + 1] & 0xFF;
                        int opt = buf[i + 2] & 0xFF;
                        if (cmd == 0xFB || cmd == 0xFD) {
                            out.write(new byte[]{(byte) 0xFF, (byte) (cmd == 0xFB ? 0xFC : 0xFE), (byte) opt});
                            out.flush();
                        }
                        i += 2;
                    }
                } else if (b != 0x00 && b != '\r') {
                    collected.write(b);
                }
            }

            if (!markerSeen && containsSequence(collected, markerBytes)) {
                markerSeen = true;
                socket.setSoTimeout(trailingDrainMs);
            }
        }

        return parseResponse(new String(collected.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String parseResponse(String full) {
        int markerPos = full.lastIndexOf(END_MARKER);
        if (markerPos < 0) {
            return full.trim();
        }

        String result = full.substring(0, markerPos);
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        String echoSuffix = "echo " + END_MARKER;
        int firstNewline = result.indexOf('\n');
        if (firstNewline >= 0) {
            String firstLine = result.substring(0, firstNewline);
            if (firstLine.endsWith(echoSuffix)) {
                result = result.substring(firstNewline + 1);
            }
        } else if (result.endsWith(echoSuffix)) {
            result = "";
        }

        return result.trim();
    }

    private static boolean containsSequence(ByteArrayOutputStream haystack, byte[] needle) {
        byte[] data = haystack.toByteArray();
        if (data.length < needle.length) return false;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
