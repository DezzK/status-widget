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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.pm.PackageInstaller;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The updater's decisions, on a real store: what a new process keeps of the last offer, what the person's
 * answers do to it, and what the installer's answers do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class UpdaterTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        shadowOf(app.getPackageManager()).getInternalMutablePackageInfo(app.getPackageName()).versionName =
                "v2.12.0";
    }

    @Test
    public void aNewProcessDropsAnOfferThatIsInstalledByNow() {
        offerStored("2.12.0");
        assertNull("the installed release is not offered", new Updater(app).state().offer);
        offerStored("2.11.2");
        assertNull("nor an older one", new Updater(app).state().offer);
        offerStored("2.13.0");
        assertEquals("2.13.0", new Updater(app).state().offer.version.toString());
    }

    @Test
    public void skippingOneReleaseLeavesTheNextOneOffered() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        long now = System.currentTimeMillis();
        assertTrue(u.state().offerWanted(now));
        u.skip();
        assertFalse(u.state().offerWanted(now));
        assertTrue(u.state().offerSkipped());
        assertFalse("remembered across processes", new Updater(app).state().offerWanted(now));

        offerStored("2.13.1");
        Updater next = new Updater(app);
        assertTrue("a newer release is offered again", next.state().offerWanted(now));
        assertEquals("the skip is still on record", "2.13.0", next.state().skippedVersion());
        next.unskip();
        assertNull(next.state().skippedVersion());
    }

    @Test
    public void laterPutsTheReleaseOffForADay() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        u.remindLater();
        long now = System.currentTimeMillis();
        assertFalse(u.state().offerWanted(now));
        assertTrue("still in the sections", u.state().offerStanding());
        assertTrue(u.state().offerWanted(now + UpdatePolicy.REMIND_LATER_MS + 1));
    }

    @Test
    public void everyChangeReachesTheListeners() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        List<UpdateState> heard = new ArrayList<>();
        Updater.Listener listener = heard::add;
        u.addListener(listener);
        u.remindLater();
        u.removeListener(listener);
        u.skip();
        assertEquals("heard while listening, and only then", 1, heard.size());
    }

    @Test
    public void declinedInTheInstallerIsLater() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        u.onInstallerStatus(PackageInstaller.STATUS_FAILURE_ABORTED, null, null);
        assertEquals(UpdateState.Phase.IDLE, u.state().phase);
        assertEquals(UpdateState.Problem.NONE, u.state().problem);
        assertFalse(u.state().offerWanted(System.currentTimeMillis()));
    }

    @Test
    public void aRefusalByTheInstallerIsToldWithItsReason() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        u.onInstallerStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null);
        assertEquals(UpdateState.Problem.INSTALL_FAILED, u.state().problemToTell());
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", u.state().problemDetail);
        assertTrue("the offer stands", u.state().offerWanted(System.currentTimeMillis()));
    }

    @Test
    public void theInstallersWaitingConfirmationIsKept() {
        offerStored("2.13.0");
        Updater u = new Updater(app);
        u.onInstallerStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null,
                new android.content.Intent("android.content.pm.action.CONFIRM_INSTALL"));
        assertEquals(UpdateState.Phase.INSTALLING, u.state().phase);
        assertTrue(u.state().awaitingConfirmation);
    }

    @Test
    public void aFailedCheckIsNotToldOverAnOfferThatStands() {
        UpdateState withOffer = state(offer("2.13.0"), UpdateState.Problem.CHECK_FAILED);
        assertEquals(UpdateState.Problem.NONE, withOffer.problemToTell());
        UpdateState without = state(null, UpdateState.Problem.CHECK_FAILED);
        assertEquals(UpdateState.Problem.CHECK_FAILED, without.problemToTell());
    }

    private void offerStored(String version) {
        new UpdateStore(app).setOffer(offer(version));
    }

    private static UpdateOffer offer(String version) {
        return new UpdateOffer(ReleaseVersion.parse(version), "/v" + version + "/status-widget-v" + version + ".apk", 10, null, "notes");
    }

    private static UpdateState state(UpdateOffer offer, UpdateState.Problem problem) {
        return new UpdateState(true, ReleaseVersion.parse("2.12.0"), offer, UpdateState.Phase.IDLE, -1, problem,
                null, false, 0, true, null, null, 0);
    }
}
