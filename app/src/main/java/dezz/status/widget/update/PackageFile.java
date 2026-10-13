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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * The downloaded package: written to disk while it is hashed, and judged against what the channel promised —
 * the disk's size and digest for the bytes, the folder's version and this app's name for the package inside.
 * Every method blocks; the updater runs them on its own executor.
 */
final class PackageFile {

    private static final int BUFFER = 64 * 1024;

    private PackageFile() {}

    /** The download was stopped from outside. */
    static final class Canceled extends IOException {
        Canceled() {
            super("canceled");
        }
    }

    /**
     * Copy {@code in} to {@code dest}, reporting whole percents of {@code expectedSize} to {@code progress}
     * (-1 while the size is unknown) and stopping when {@code cancel} is raised.
     *
     * @return the SHA-256 of what was written, lowercase hex
     * @throws IOException a short body (a dropped connection reads like a clean end), a read failure, or
     *         {@link Canceled}
     */
    static String copy(InputStream in, File dest, long expectedSize, IntConsumer progress, AtomicBoolean cancel)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buf = new byte[BUFFER];
        long written = 0;
        int lastPercent = Integer.MIN_VALUE;
        try (OutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) throw new Canceled();
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                written += n;
                int percent = expectedSize > 0 ? (int) Math.min(100, written * 100 / expectedSize) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.accept(percent);
                }
            }
        }
        if (expectedSize > 0 && written != expectedSize) {
            throw new IOException("got " + written + " of " + expectedSize + " bytes");
        }
        return hex(digest.digest());
    }

    /** The SHA-256 of a file already on disk, lowercase hex. */
    static String sha256Of(File f) throws IOException {
        MessageDigest digest = sha256();
        byte[] buf = new byte[BUFFER];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        return hex(digest.digest());
    }

    /** Are these the bytes the channel listed: its size, and its digest where the disk gave one. */
    static boolean matches(UpdateOffer offer, long size, String sha256) {
        return size == offer.apkSize && (offer.apkSha256 == null || offer.apkSha256.equalsIgnoreCase(sha256));
    }

    /**
     * What is wrong with the package at {@code apk} as {@code offer}'s release of this app, or null when
     * nothing is: it must read as a package, be this app, and carry the version its folder is named for. The
     * channel's own rule — the folder names the release inside it — checked here, so a publishing slip reads
     * as one instead of as a confirmation dialog for the wrong thing. Signatures and downgrades are the
     * installer's to judge.
     */
    @Nullable
    static String packageProblem(Context ctx, File apk, UpdateOffer offer) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? pm.getPackageArchiveInfo(apk.getPath(), PackageManager.PackageInfoFlags.of(0))
                : pm.getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) return "not a package";
        if (!ctx.getPackageName().equals(info.packageName)) return "package " + info.packageName;
        ReleaseVersion v = ReleaseVersion.parse(info.versionName);
        if (!offer.version.equals(v)) return "version " + info.versionName + " in the folder of " + offer.version;
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Android runtime has it
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
