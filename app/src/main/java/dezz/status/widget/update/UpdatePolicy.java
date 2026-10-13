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

import androidx.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * When the channel is asked, and whether a release it offers is put to the person — the timing rules, pure,
 * on the wall clock (they span reboots). A clock set back must not freeze them: a moment stamped «in the
 * future» counts as long past, and a snooze longer than one «later» is no snooze at all.
 * {@code UpdatePolicyTest} is their truth table.
 */
public final class UpdatePolicy {

    /** How often the channel is asked by itself — also the period of the background job. */
    public static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    /** After a check that could not reach the channel, how soon another may try. */
    public static final long RETRY_AFTER_FAIL_MS = TimeUnit.MINUTES.toMillis(30);
    /** «Напомнить позже»: the release is put to the person again after this long. */
    public static final long REMIND_LATER_MS = TimeUnit.DAYS.toMillis(1);

    private UpdatePolicy() {}

    /** May an automatic check run now, given when the last one succeeded and when the last one failed. */
    public static boolean checkDue(long nowMs, long lastOkMs, long lastFailMs) {
        return passed(lastOkMs, nowMs, CHECK_INTERVAL_MS) && passed(lastFailMs, nowMs, RETRY_AFTER_FAIL_MS);
    }

    /** Is {@code offer} to be put to the person now: there is one, it was not skipped, and it is not snoozed. */
    public static boolean offerWanted(@Nullable UpdateOffer offer, @Nullable String skipped,
                                      @Nullable String snoozed, long snoozedUntilMs, long nowMs) {
        if (offer == null) return false;
        String version = offer.version.toString();
        if (version.equals(skipped)) return false;
        return !(version.equals(snoozed) && snoozing(snoozedUntilMs, nowMs));
    }

    static boolean snoozing(long untilMs, long nowMs) {
        return nowMs < untilMs && untilMs - nowMs <= REMIND_LATER_MS;
    }

    /** {@code span} has passed since {@code sinceMs} (0 = never happened; later than now = a clock set back). */
    private static boolean passed(long sinceMs, long nowMs, long span) {
        return sinceMs <= 0 || nowMs < sinceMs || nowMs - sinceMs >= span;
    }
}
