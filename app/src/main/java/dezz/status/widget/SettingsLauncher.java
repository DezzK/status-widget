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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * Centralised launcher for system Settings screens. Many car head units (notably the QUALCOMM
 * KX11 stack used in Geely Monjaro) ship a stripped-down system Settings package where the
 * standard intent actions like {@link Settings#ACTION_USAGE_ACCESS_SETTINGS} aren't registered
 * by the dispatcher even though the underlying activity is still present. This class walks a
 * cascade of fallbacks — action variants first, then known direct component names — and only
 * actually launches an intent after {@code resolveActivity} confirms a target exists, so a
 * missing activity never crashes the app.
 */
final class SettingsLauncher {
    private static final String TAG = "SettingsLauncher";

    /**
     * Direct component names that AOSP and several OEM ROMs use for the Usage Access screen.
     * The dispatcher entry (action {@code ACTION_USAGE_ACCESS_SETTINGS}) is sometimes removed by
     * car-headunit ROMs even though one of these activities still exists; trying them by
     * explicit component bypasses the dispatcher.
     */
    private static final String[] USAGE_ACCESS_COMPONENTS = {
            "com.android.settings/.Settings$UsageAccessSettingsActivity",
            "com.android.settings/.applications.UsageAccessSettingsActivity",
            "com.android.settings/.UsageAccessSettingsActivity",
            "com.android.settings/com.android.settings.Settings$UsageAccessSettingsActivity",
    };

    private SettingsLauncher() {}

    /**
     * Try every known way to take the user to the "Usage Access" settings screen for our app.
     *
     * @return {@code true} if some screen was launched; {@code false} if none of the variants
     *         resolved on this device — caller should show a user-visible error.
     */
    static boolean openUsageAccessSettings(Activity activity) {
        Uri appUri = Uri.parse("package:" + activity.getPackageName());

        // 1. Standard action with package URI — preferred path on stock Android.
        if (tryStart(activity, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, appUri))) {
            return true;
        }
        // 2. Standard action without URI — fallback when (1) doesn't resolve.
        if (tryStart(activity, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) {
            return true;
        }
        // 3. Direct component names. The ROM may have gutted the dispatcher but not the
        //    activity itself; explicit component bypass works on those builds.
        for (String spec : USAGE_ACCESS_COMPONENTS) {
            ComponentName component = ComponentName.unflattenFromString(spec);
            if (component == null) continue;
            Intent intent = new Intent();
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(activity, intent)) {
                Log.i(TAG, "Opened Usage Access via direct component " + spec);
                return true;
            }
        }
        // 4. Last resort — app details, where the user might find Usage Access nested.
        if (tryStart(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))) {
            return true;
        }
        Log.w(TAG, "No Usage Access settings activity is reachable on this device");
        return false;
    }

    /**
     * Resolve-then-start helper. Returns {@code true} only if the intent actually launched.
     * Swallows {@link Throwable} so {@code SecurityException} from quirky OEM activity managers
     * doesn't crash the caller either.
     */
    private static boolean tryStart(Activity activity, Intent intent) {
        try {
            if (intent.resolveActivity(activity.getPackageManager()) == null) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed for " + intent, t);
            return false;
        }
    }
}
