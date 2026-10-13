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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A listing of a public folder as the disk's API answers it (the shape of a real answer, trimmed): entries,
 * folders told from files, the file's size and digest, and the folder's total for paging. org.json is the
 * platform's, so this runs on Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = android.app.Application.class)
public class YandexDiskTest {

    private static final String LISTING = "{\"public_key\":\"https://disk.yandex.ru/d/x\",\"_embedded\":{"
            + "\"items\":["
            + "{\"name\":\"v2.13.0\",\"type\":\"dir\",\"path\":\"/v2.13.0\"},"
            + "{\"name\":\"status-widget-v2.13.0.apk\",\"type\":\"file\",\"path\":\"/v2.13.0/status-widget-v2.13.0.apk\","
            + "\"size\":31457280,\"md5\":\"d41d8cd98f00b204e9800998ecf8427e\","
            + "\"sha256\":\"E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855\"}],"
            + "\"limit\":200,\"offset\":0,\"path\":\"/\",\"total\":7},"
            + "\"name\":\"updates\",\"type\":\"dir\",\"path\":\"/\"}";

    @Test
    public void readsEntriesAndThePageTotal() throws Exception {
        YandexDisk.Page page = YandexDisk.parsePage(LISTING);
        assertEquals(7, page.total);
        assertEquals(2, page.items.size());
        YandexDisk.Item dir = page.items.get(0);
        assertTrue(dir.dir);
        assertEquals("/v2.13.0", dir.path);
        assertNull(dir.sha256);
        YandexDisk.Item apk = page.items.get(1);
        assertFalse(apk.dir);
        assertEquals(31457280L, apk.size);
        assertEquals("the digest is kept in one case", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                apk.sha256);
    }

    @Test
    public void aFileAnswerListsNothing() throws Exception {
        YandexDisk.Page page = YandexDisk.parsePage("{\"name\":\"a.apk\",\"type\":\"file\",\"path\":\"/a.apk\"}");
        assertTrue(page.items.isEmpty());
        assertEquals(0, page.total);
    }
}
