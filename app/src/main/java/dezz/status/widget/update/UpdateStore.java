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
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * What the updater keeps between runs: the release last offered, the version the person skipped, the one
 * they put off and until when, when the channel was last read (and last failed to be), and whether it is read
 * by itself at all. Device-protected storage — the widget's service starts before the head unit unlocks
 * ({@code BootReceiver}), and reading credential-protected preferences then throws.
 */
final class UpdateStore {

    private static final String FILE = "update_state";
    private static final String OFFER = "offer";
    private static final String SKIPPED = "skipped";
    private static final String SNOOZED = "snoozed";
    private static final String SNOOZED_UNTIL = "snoozedUntil";
    private static final String LAST_OK = "lastCheckOk";
    private static final String LAST_FAIL = "lastCheckFail";
    private static final String AUTO_CHECK = "autoCheck";

    private final SharedPreferences prefs;

    UpdateStore(Context ctx) {
        prefs = ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Nullable
    UpdateOffer offer() {
        return UpdateOffer.fromJson(prefs.getString(OFFER, null));
    }

    void setOffer(@Nullable UpdateOffer offer) {
        prefs.edit().putString(OFFER, offer != null ? offer.toJson() : null).apply();
    }

    @Nullable
    String skipped() {
        return prefs.getString(SKIPPED, null);
    }

    void setSkipped(@Nullable String version) {
        prefs.edit().putString(SKIPPED, version).apply();
    }

    @Nullable
    String snoozed() {
        return prefs.getString(SNOOZED, null);
    }

    long snoozedUntil() {
        return prefs.getLong(SNOOZED_UNTIL, 0);
    }

    void setSnoozed(String version, long untilMs) {
        prefs.edit().putString(SNOOZED, version).putLong(SNOOZED_UNTIL, untilMs).apply();
    }

    long lastCheckOk() {
        return prefs.getLong(LAST_OK, 0);
    }

    long lastCheckFail() {
        return prefs.getLong(LAST_FAIL, 0);
    }

    void setCheckOk(long nowMs) {
        prefs.edit().putLong(LAST_OK, nowMs).putLong(LAST_FAIL, 0).apply();
    }

    void setCheckFailed(long nowMs) {
        prefs.edit().putLong(LAST_FAIL, nowMs).apply();
    }

    boolean autoCheck() {
        return prefs.getBoolean(AUTO_CHECK, true);
    }

    void setAutoCheck(boolean on) {
        prefs.edit().putBoolean(AUTO_CHECK, on).apply();
    }
}
