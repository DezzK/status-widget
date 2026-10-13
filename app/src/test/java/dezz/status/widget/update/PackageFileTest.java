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
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The download is judged by its bytes: hashed as it is written, a short body refused (a dropped connection
 * reads like a clean end), a cancel honored mid-way, and the result compared with the size and digest the
 * channel listed.
 */
public class PackageFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** SHA-256 of "abc", the FIPS 180-2 test vector. */
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void hashesWhatItWritesAndReportsWholePercents() throws IOException {
        File dest = tmp.newFile();
        List<Integer> seen = new ArrayList<>();
        String sha = PackageFile.copy(bytes("abc"), dest, 3, seen::add, new AtomicBoolean());
        assertEquals(ABC, sha);
        assertEquals(3, dest.length());
        assertEquals(ABC, PackageFile.sha256Of(dest));
        assertEquals(100, (int) seen.get(seen.size() - 1));
    }

    @Test
    public void aShortBodyIsNoPackage() throws IOException {
        try {
            PackageFile.copy(bytes("ab"), tmp.newFile(), 3, p -> { }, new AtomicBoolean());
            fail("a body shorter than listed was accepted");
        } catch (IOException expected) {
            assertFalse(expected instanceof PackageFile.Canceled);
        }
    }

    @Test
    public void aCancelStopsTheCopy() throws IOException {
        AtomicBoolean cancel = new AtomicBoolean(true);
        try {
            PackageFile.copy(bytes("abc"), tmp.newFile(), 3, p -> { }, cancel);
            fail("the copy ran past a cancel");
        } catch (PackageFile.Canceled expected) {
            // stopped
        }
    }

    @Test
    public void matchesTheListedSizeAndDigest() {
        UpdateOffer listed = new UpdateOffer(ReleaseVersion.parse("2.13.0"), "/a.apk", 3, ABC, null);
        assertTrue(PackageFile.matches(listed, 3, ABC.toUpperCase()));
        assertFalse("another digest", PackageFile.matches(listed, 3, ABC.replace('b', 'c')));
        assertFalse("another size", PackageFile.matches(listed, 4, ABC));
        UpdateOffer noDigest = new UpdateOffer(ReleaseVersion.parse("2.13.0"), "/a.apk", 3, null, null);
        assertTrue("no digest listed: the size decides", PackageFile.matches(noDigest, 3, ABC));
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }
}
