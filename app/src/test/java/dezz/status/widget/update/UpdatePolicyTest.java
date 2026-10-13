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

import static dezz.status.widget.update.UpdatePolicy.CHECK_INTERVAL_MS;
import static dezz.status.widget.update.UpdatePolicy.REMIND_LATER_MS;
import static dezz.status.widget.update.UpdatePolicy.RETRY_AFTER_FAIL_MS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The truth table of when the channel is asked and when a release is put to the person — on the wall clock,
 * which a head unit without a network sets back and forth, so a clock set back must free the rules, never
 * freeze them for the time it jumped.
 */
public class UpdatePolicyTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final UpdateOffer OFFER =
            new UpdateOffer(ReleaseVersion.parse("2.13.0"), "/v2.13.0/status-widget-v2.13.0.apk", 10, null, null);

    @Test
    public void checksOnTheIntervalAndBacksOffAfterAFailure() {
        assertTrue("never checked", UpdatePolicy.checkDue(NOW, 0, 0));
        assertFalse(UpdatePolicy.checkDue(NOW, NOW - CHECK_INTERVAL_MS + 1, 0));
        assertTrue(UpdatePolicy.checkDue(NOW, NOW - CHECK_INTERVAL_MS, 0));
        assertFalse("a failure holds the next try back",
                UpdatePolicy.checkDue(NOW, 0, NOW - RETRY_AFTER_FAIL_MS + 1));
        assertTrue(UpdatePolicy.checkDue(NOW, 0, NOW - RETRY_AFTER_FAIL_MS));
    }

    @Test
    public void aClockSetBackDoesNotFreezeTheChecks() {
        assertTrue(UpdatePolicy.checkDue(NOW, NOW + 3 * CHECK_INTERVAL_MS, 0));
        assertTrue(UpdatePolicy.checkDue(NOW, 0, NOW + RETRY_AFTER_FAIL_MS));
    }

    @Test
    public void anOfferIsWantedUnlessSkippedOrPutOff() {
        assertFalse("nothing offered", UpdatePolicy.offerWanted(null, null, null, 0, NOW));
        assertTrue(UpdatePolicy.offerWanted(OFFER, null, null, 0, NOW));
        assertFalse("skipped", UpdatePolicy.offerWanted(OFFER, "2.13.0", null, 0, NOW));
        assertTrue("another version skipped", UpdatePolicy.offerWanted(OFFER, "2.12.0", null, 0, NOW));
        assertFalse("put off", UpdatePolicy.offerWanted(OFFER, null, "2.13.0", NOW + REMIND_LATER_MS, NOW));
        assertTrue("put off, and the time came", UpdatePolicy.offerWanted(OFFER, null, "2.13.0", NOW, NOW));
        assertTrue("an older release was put off, not this one",
                UpdatePolicy.offerWanted(OFFER, null, "2.12.0", NOW + REMIND_LATER_MS, NOW));
    }

    @Test
    public void aSnoozeLongerThanOneLaterIsNoSnooze() {
        // Put off, then the clock set back a week: the reminder must not wait the extra week.
        assertTrue(UpdatePolicy.offerWanted(OFFER, null, "2.13.0", NOW + REMIND_LATER_MS + 1, NOW));
    }
}
