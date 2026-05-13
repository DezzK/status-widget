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

/**
 * Abstraction for executing shell commands on the device via an out-of-band
 * privileged channel (ADB-over-network or Telnet listener).
 * Implementations: {@link AdbTransport}, {@link TelnetTransport}.
 */
public interface ShellTransport {
    /** Human-readable description for logs and toasts, e.g. {@code "ADB 127.0.0.1:5555"}. */
    String describe();

    /** Execute a shell command and return its trimmed output. Reusable on the same instance. */
    String exec(String command) throws Exception;

    /** Close the connection and release resources. */
    void close();

    /**
     * Format a {@code host:port} pair, wrapping IPv6 hosts in brackets so the
     * port doesn't get misread as part of the address (e.g. {@code [fe80::1]:5555}).
     */
    static String formatHostPort(String host, int port) {
        if (host != null && host.indexOf(':') >= 0) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }
}
