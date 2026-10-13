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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.update.UpdateState.Action;
import dezz.status.widget.update.UpdateState.Phase;
import dezz.status.widget.update.UpdateState.Problem;

/**
 * The answers every surface offers — the settings section and the card over other apps — come from one
 * decision; this is its truth table.
 */
public class UpdateStateTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final UpdateOffer OFFER =
            new UpdateOffer(ReleaseVersion.parse("2.13.0"), "/v2.13.0/status-widget-v2.13.0.apk", 10, null, null);

    @Test
    public void theActionOfTheMoment() {
        assertEquals("nothing offered", Action.NONE, state(null, Phase.IDLE, Problem.NONE, false, null).action());
        assertEquals(Action.INSTALL, state(OFFER, Phase.IDLE, Problem.NONE, false, null).action());
        assertEquals(Action.RETRY, state(OFFER, Phase.IDLE, Problem.DOWNLOAD_FAILED, false, null).action());
        assertEquals("a failed check says nothing over an offer that stands", Action.INSTALL,
                state(OFFER, Phase.IDLE, Problem.CHECK_FAILED, false, null).action());
        assertEquals(Action.CANCEL, state(OFFER, Phase.DOWNLOADING, Problem.NONE, false, null).action());
        assertEquals("handed over, no answer yet", Action.NONE,
                state(OFFER, Phase.INSTALLING, Problem.NONE, false, null).action());
        assertEquals(Action.CONFIRM, state(OFFER, Phase.INSTALLING, Problem.NONE, true, null).action());
        assertEquals(Action.NONE, state(OFFER, Phase.CHECKING, Problem.NONE, false, null).action());
        assertEquals("a skipped release may still be installed by hand", Action.INSTALL,
                state(OFFER, Phase.IDLE, Problem.NONE, false, "2.13.0").action());
    }

    @Test
    public void theAnswersThatMoveTheInstallationOnLead() {
        assertTrue(Action.INSTALL.movesOn());
        assertTrue(Action.RETRY.movesOn());
        assertTrue(Action.CONFIRM.movesOn());
        assertFalse("stopping a download is the second answer", Action.CANCEL.movesOn());
        assertFalse(Action.NONE.movesOn());
    }

    @Test
    public void aPutOffReleaseStaysInTheSectionsAndQuietsTheReminders() {
        UpdateState putOff = new UpdateState(true, ReleaseVersion.parse("2.12.0"), OFFER, Phase.IDLE, -1,
                Problem.NONE, null, false, NOW, true, null, "2.13.0", NOW + UpdatePolicy.REMIND_LATER_MS);
        assertTrue("the section keeps it until it is installed or skipped", putOff.offerStanding());
        assertFalse("the notification and the card are quiet", putOff.offerWanted(NOW));
        assertFalse("a reminder put off offers no second «later»", putOff.canPutOff(NOW));
        assertTrue(putOff.canSkip());
        UpdateState skipped = state(OFFER, Phase.IDLE, Problem.NONE, false, "2.13.0");
        assertFalse("skipped: gone from the sections too", skipped.offerStanding());
        assertFalse(state(null, Phase.IDLE, Problem.NONE, false, null).offerStanding());
    }

    @Test
    public void laterAndSkipOnlyWhileAnOfferWaits() {
        UpdateState waiting = state(OFFER, Phase.IDLE, Problem.NONE, false, null);
        assertTrue(waiting.canPutOff(NOW));
        assertTrue(waiting.canSkip());
        UpdateState skipped = state(OFFER, Phase.IDLE, Problem.NONE, false, "2.13.0");
        assertFalse(skipped.canPutOff(NOW));
        assertFalse(skipped.canSkip());
        UpdateState downloading = state(OFFER, Phase.DOWNLOADING, Problem.NONE, false, null);
        assertFalse(downloading.canPutOff(NOW));
        assertFalse(downloading.canSkip());
    }

    private static UpdateState state(UpdateOffer offer, Phase phase, Problem problem, boolean awaiting,
                                     String skipped) {
        return new UpdateState(true, ReleaseVersion.parse("2.12.0"), offer, phase, -1, problem, null, awaiting,
                NOW, true, skipped, null, 0);
    }
}
