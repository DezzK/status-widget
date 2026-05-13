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
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Shell transport over the ADB protocol (adblib).
 * <p>
 * Encapsulates all ADB-specific concerns: the RSA key pair (lazily generated
 * once and cached for the app's lifetime), Base64 encoder, and the handshake.
 * Ported from the stealth project — same head units, same protocol.
 */
public class AdbTransport implements ShellTransport {
    private static final String TAG = "AdbTransport";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    /**
     * Bound on the ADB handshake (CNXN/AUTH exchange). Without this, adblib waits
     * {@code Long.MAX_VALUE} — a port that accepts TCP but doesn't speak ADB
     * (Telnet banner, HTTP, etc.) would otherwise hang the probe thread forever.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;
    private static final String KEY_FILE_PREFIX = "adb_key";

    private static final AdbBase64 ADB_BASE64 = data -> Base64.encodeToString(data, Base64.DEFAULT);

    /** Shared key pair — generated once, reused across all AdbTransport instances. */
    private static volatile AdbCrypto sharedCrypto;

    private final Socket socket;
    private final AdbConnection connection;
    private final String host;
    private final int port;

    private AdbTransport(Socket socket, AdbConnection connection, String host, int port) {
        this.socket = socket;
        this.connection = connection;
        this.host = host;
        this.port = port;
    }

    /**
     * Connect to ADB on the given host and port and perform the ADB handshake.
     * The RSA key pair is loaded or generated on first call and cached.
     */
    public static AdbTransport connect(Context context, String host, int port) throws Exception {
        AdbCrypto crypto = getOrCreateCrypto(context);

        Socket socket = new Socket();
        AdbConnection connection = null;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            connection = AdbConnection.create(socket, crypto);
            if (!connection.connect(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS, false)) {
                throw new IOException("ADB handshake timeout");
            }
            return new AdbTransport(socket, connection, host, port);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
            try { socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public String describe() {
        return "ADB " + ShellTransport.formatHostPort(host, port);
    }

    // ── Safe probe (no adblib) ────────────────────────────────────────

    /** ADB protocol command codes (little-endian on the wire). */
    private static final int ADB_CMD_CNXN = 0x4e584e43; // "CNXN"
    private static final int ADB_CMD_AUTH = 0x48545541; // "AUTH"
    private static final int ADB_HEADER_LEN = 24;
    private static final int PROBE_READ_TIMEOUT_MS = 2000;

    /**
     * Manual ADB handshake check that does NOT use adblib. Used during port discovery
     * where the peer's identity is unknown.
     * <p>
     * Why not adblib? Its {@code parseAdbMessage} blindly does
     * {@code new byte[header.payloadLength]} on whatever the peer sends. A non-ADB
     * peer (Telnet banner, HTTP, random TCP service) makes the parser interpret
     * garbage as a huge {@code payloadLength} and the {@link OutOfMemoryError} kills
     * the entire process — adblib's connection thread catches {@code Exception}, not
     * {@code Throwable}, so the OOM propagates uncaught.
     * <p>
     * This implementation reads exactly {@value #ADB_HEADER_LEN} bytes and checks the
     * ADB header invariant {@code magic == command ^ 0xFFFFFFFF}.
     *
     * @return true iff the peer responded with a valid ADB CNXN or AUTH header.
     */
    public static boolean probe(String host, int port) {
        return probe(host, port, null);
    }

    /**
     * Variant of {@link #probe(String, int)} that publishes the underlying {@link Socket} to
     * {@code socketSink} as soon as it's constructed. The caller can then close the socket
     * externally (e.g. when a sibling probe has already succeeded) to unblock the slow
     * {@code Socket.connect}/{@code read} calls that {@code Thread.interrupt()} doesn't reach.
     * The probe still closes the socket itself in its own finally — registering with the sink
     * is purely additive.
     */
    public static boolean probe(String host, int port, @Nullable Consumer<Socket> socketSink) {
        Socket socket = new Socket();
        if (socketSink != null) {
            socketSink.accept(socket);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_READ_TIMEOUT_MS);

            byte[] banner = "host::\0".getBytes(StandardCharsets.US_ASCII);
            ByteBuffer hdr = ByteBuffer.allocate(ADB_HEADER_LEN + banner.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(ADB_CMD_CNXN);
            hdr.putInt(0x01000000);                  // version
            hdr.putInt(4096);                        // maxData
            hdr.putInt(banner.length);
            int checksum = 0;
            for (byte b : banner) checksum += b & 0xFF;
            hdr.putInt(checksum);
            hdr.putInt(ADB_CMD_CNXN ^ 0xFFFFFFFF);   // magic
            hdr.put(banner);

            OutputStream out = socket.getOutputStream();
            out.write(hdr.array());
            out.flush();

            byte[] resp = new byte[ADB_HEADER_LEN];
            InputStream in = socket.getInputStream();
            int total = 0;
            while (total < ADB_HEADER_LEN) {
                int n = in.read(resp, total, ADB_HEADER_LEN - total);
                if (n < 0) return false;
                total += n;
            }

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            int respCommand = rb.getInt();
            rb.position(20);
            int respMagic = rb.getInt();

            if (respMagic != (respCommand ^ 0xFFFFFFFF)) return false;
            // AUTH is the usual response from an adbd that hasn't authorised our key yet (it
            // asks us to sign a challenge); CNXN means the key is already on the device's
            // adb_keys allow-list — both cases prove the peer speaks the ADB protocol, so
            // both count as a successful probe.
            return respCommand == ADB_CMD_AUTH || respCommand == ADB_CMD_CNXN;
        } catch (Exception e) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public String exec(String command) throws Exception {
        AdbStream stream = connection.open("shell:" + command);
        // adblib's AdbStream.read() returns one payload at a time (max 4 KiB). For small
        // outputs like `appops set ...` one read is enough, but anything larger gets
        // truncated. Loop until the remote closes the stream (read() returns null) and
        // concatenate so the public exec() contract — "the trimmed command output" —
        // actually holds.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            byte[] chunk;
            try {
                chunk = stream.read();
            } catch (java.io.IOException eof) {
                // adblib throws "Stream closed" for the normal terminal EOF case.
                break;
            }
            if (chunk == null || chunk.length == 0) break;
            buffer.write(chunk);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    @Override
    public void close() {
        try { connection.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ── Key pair management ───────────────────────────────────────────

    private static AdbCrypto getOrCreateCrypto(Context context) {
        AdbCrypto local = sharedCrypto;
        if (local == null) {
            synchronized (AdbTransport.class) {
                local = sharedCrypto;
                if (local == null) {
                    local = loadOrGenerateKeyPair(context.getApplicationContext());
                    sharedCrypto = local;
                }
            }
        }
        return local;
    }

    private static AdbCrypto loadOrGenerateKeyPair(Context context) {
        // Device-protected so the key survives reboots and is available pre-unlock,
        // matching how the rest of the app stores boot-critical state.
        Context deviceContext = context.createDeviceProtectedStorageContext();
        File privateKey = new File(deviceContext.getFilesDir(), KEY_FILE_PREFIX);
        File publicKey = new File(deviceContext.getFilesDir(), KEY_FILE_PREFIX + ".pub");

        try {
            if (privateKey.exists() && publicKey.exists()) {
                return AdbCrypto.loadAdbKeyPair(ADB_BASE64, privateKey, publicKey);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load stored ADB key pair, regenerating", e);
        }

        try {
            AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(ADB_BASE64);
            crypto.saveAdbKeyPair(privateKey, publicKey);
            return crypto;
        } catch (Exception e) {
            throw new RuntimeException("Cannot generate ADB key pair", e);
        }
    }
}
