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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.core.content.IntentCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands the package to the system's installer the ordinary way: a package-installer session of this app's
 * own package, committed with a status receiver. The system asks the person — to allow installing from this
 * app if they never have, then to confirm — and the receiver carries its answers to the {@link Updater}.
 * Nothing here can install without the person. Android 12+ lets an installer update its own package unasked,
 * but only one that targets a newer SDK than this app's 28, so every update here is a confirmed one.
 */
final class SystemInstaller {

    private static final int BUFFER = 64 * 1024;
    /** The session's one file. */
    private static final String SPLIT = "base.apk";

    private SystemInstaller() {}

    /** Stream {@code apk} into a fresh session and commit it. Blocks. */
    static void commit(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        // A session left by an earlier attempt (its confirmation dismissed, the process gone) would linger
        // until the system reaps it; this app runs one installation at a time.
        for (PackageInstaller.SessionInfo s : installer.getMySessions()) {
            try {
                installer.abandonSession(s.getSessionId());
            } catch (RuntimeException ignored) {
                // already finished
            }
        }
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(SPLIT, 0, apk.length())) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                session.commit(statusReceiver(ctx, id).getIntentSender());
            } catch (IOException | RuntimeException e) {
                session.abandon();
                throw e;
            }
        }
    }

    private static PendingIntent statusReceiver(Context ctx, int sessionId) {
        Intent intent = new Intent(ctx, StatusReceiver.class).setPackage(ctx.getPackageName());
        // Mutable: the installer writes its answer into this intent's extras. Explicit, so Android 14's rule
        // against mutable implicit intents does not apply.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, sessionId, intent, flags);
    }

    /**
     * The installer's answers about a session committed here: it needs the person (its confirmation, which
     * the updater opens), it is done, or it refused. Not exported — only the installer, through the pending
     * intent above, can reach it.
     */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Intent confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
            Updater.get(context).onInstallerStatus(status, message, confirm);
        }
    }
}
