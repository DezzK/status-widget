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

package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

/**
 * Whether the car is moving, as the positional feed answers it for the update card: only a fresh fix at speed
 * says so — no fix, a standing car's jitter and a fix gone stale all let the card come up.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class GnssProviderMotionTest {

    private GnssProvider gnss;

    @Before
    public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        shadowOf(app.getSystemService(LocationManager.class)).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        gnss = new GnssProvider(app, new Handler(Looper.getMainLooper()));
        gnss.setNeeds(new GnssProvider.Listener() { }, GnssProvider.NEED_LOCATION);
    }

    @Test
    public void noFixIsNotMoving() {
        assertFalse(gnss.moving());
    }

    @Test
    public void aStandingCarsJitterIsNotMoving() {
        deliver(0.6f);
        assertFalse(gnss.moving());
    }

    @Test
    public void aFreshFixAtSpeedIsMoving() {
        deliver(8f);
        assertTrue(gnss.moving());
    }

    @Test
    public void aFixGoneStaleIsNotMoving() {
        deliver(8f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GnssProvider.FIX_STALE_MS + 1));
        assertFalse(gnss.moving());
    }

    private void deliver(float speedMps) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(55.75);
        fix.setLongitude(37.62);
        fix.setSpeed(speedMps);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(LocationManager.class)).simulateLocation(fix);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
