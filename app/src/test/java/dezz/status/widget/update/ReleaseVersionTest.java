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

import org.junit.Test;

/**
 * The one reader of a version on the device: the build's VERSION_NAME and a channel's folder names must read
 * alike and order as releases do, or a folder could be offered as newer than what is installed when it is not.
 */
public class ReleaseVersionTest {

    @Test
    public void readsTheFormsTheBuildAndTheChannelUse() {
        assertEquals("3.16.0", ReleaseVersion.parse("v3.16.0").toString());
        assertEquals("3.16.0", ReleaseVersion.parse("3.16.0").toString());
        assertEquals("the build's patch is optional", "3.16.0", ReleaseVersion.parse("v3.16").toString());
        assertEquals("3.16.1-beta2", ReleaseVersion.parse("3.16.1-beta2").toString());
        assertEquals(ReleaseVersion.parse("v3.16.0"), ReleaseVersion.parse("3.16"));
    }

    @Test
    public void somethingElseIsNoVersion() {
        assertNull(ReleaseVersion.parse("old"));
        assertNull(ReleaseVersion.parse("3"));
        assertNull(ReleaseVersion.parse("3.16.0.1"));
        assertNull(ReleaseVersion.parse("<unknown>"));
        assertNull(ReleaseVersion.parse(null));
        assertNull(ReleaseVersion.parse("3.16.0-"));
    }

    @Test
    public void ordersByTheNumbersNotTheText() {
        assertTrue(v("3.10.0").isNewerThan(v("3.9.9")));
        assertTrue(v("4.0.0").isNewerThan(v("3.99.99")));
        assertTrue(v("3.16.1").isNewerThan(v("3.16.0")));
        assertFalse(v("3.16.0").isNewerThan(v("3.16.0")));
    }

    @Test
    public void aReleaseIsNewerThanItsPreReleases() {
        assertTrue(v("3.16.0").isNewerThan(v("3.16.0-beta1")));
        assertTrue(v("3.16.0-beta2").isNewerThan(v("3.16.0-beta1")));
        assertTrue(v("3.16.0-beta1").isNewerThan(v("3.15.9")));
        assertFalse(v("3.16.0-rc1").isNewerThan(v("3.16.0")));
    }

    private static ReleaseVersion v(String s) {
        return ReleaseVersion.parse(s);
    }
}
