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

import dezz.status.widget.Permissions;
import dezz.status.widget.shell.PrivilegedShell;

/**
 * Installing the offered release on a head unit. The package goes to the system's installer the ordinary way
 * ({@link Updater#install}); what a head unit adds is the permission to install from this app at all, which
 * the installer asks for on a settings screen many units do not have. So where it is missing, the unit's own
 * shell is asked for it first — only then, never at start-up — and without a shell the installer asks the
 * driver itself.
 */
public final class UpdateInstall {

    private UpdateInstall() {}

    /** Do what the button of {@code action} says — the same for the card over other apps and the settings. */
    public static void perform(Context ctx, UpdateState.Action action) {
        Updater updater = Updater.get(ctx);
        switch (action) {
            case INSTALL, RETRY -> start(ctx);
            case CONFIRM -> updater.confirmInstall();
            case CANCEL -> updater.cancel();
            default -> { }
        }
    }

    /**
     * Install the offered release, asking the shell for the permission first where it is missing. Whatever the
     * shell manages, the installer follows: granted, it opens straight to the confirmation; not, it asks for
     * the permission itself on its way there. Main thread.
     */
    private static void start(Context ctx) {
        Updater updater = Updater.get(ctx);
        if (Permissions.canInstallPackages(ctx)) {
            updater.install();
            return;
        }
        PrivilegedShell.Request request =
                PrivilegedShell.Request.forPackage(ctx.getPackageName()).withInstallPackages().build();
        PrivilegedShell.get(ctx).ensurePrivileges(request, result -> updater.install());
    }
}
