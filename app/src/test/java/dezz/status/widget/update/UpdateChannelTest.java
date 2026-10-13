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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The channel's layout as the publisher follows it: which folders are releases above the installed one and in
 * which order they are tried, which file of a release is the app's package, and which is the notes.
 */
public class UpdateChannelTest {

    private static final String NAME = "status-widget";
    private static final ReleaseVersion INSTALLED = ReleaseVersion.parse("v2.12.0");
    private static final ReleaseVersion V2_13 = ReleaseVersion.parse("2.13.0");

    @Test
    public void releasesAboveTheInstalledOneNewestFirst() {
        List<YandexDisk.Item> items = Arrays.asList(dir("2.9.0"), dir("v2.13.0"), dir("2.12.0"), dir("2.12.1"),
                dir("drafts"), file("2.14.0"), dir("2.13.0-beta1"), dir("2.10.0"));
        List<String> names = new ArrayList<>();
        for (YandexDisk.Item it : UpdateChannel.releasesAbove(items, INSTALLED)) names.add(it.name);
        assertEquals("by version, not by text; not the installed one, nor a file, nor a folder of something else",
                Arrays.asList("v2.13.0", "2.13.0-beta1", "2.12.1"), names);
    }

    @Test
    public void thePackageIsTheFoldersVersionWrittenAnyWay() {
        YandexDisk.Item apk = file("status-widget-v2.13.0.apk");
        assertEquals(apk, UpdateChannel.packageOf(
                Arrays.asList(file(UpdateChannel.notesFile(null)), apk), NAME, V2_13));
        assertEquals("no «v», another case", "Status-Widget-2.13.apk", UpdateChannel.packageOf(
                List.of(file("Status-Widget-2.13.apk")), NAME, V2_13).name);
    }

    @Test
    public void nothingElseInTheFolderIsThePackage() {
        assertNull("a package of another version left in the folder", UpdateChannel.packageOf(
                List.of(file("status-widget-v2.12.0.apk")), NAME, V2_13));
        assertNull("a debug build left beside the release", UpdateChannel.packageOf(
                List.of(file("status-widget-v2.13.0-debug.apk")), NAME, V2_13));
        assertNull("another app's package", UpdateChannel.packageOf(
                List.of(file("gnss-client-v2.13.0.apk")), NAME, V2_13));
        assertNull("a folder named like a package is not one", UpdateChannel.packageOf(
                List.of(dir("status-widget-v2.13.0.apk")), NAME, V2_13));
    }

    @Test
    public void theNotesAreTheirOwnFileByName() {
        YandexDisk.Item notes = file("Release-Notes.txt");
        assertEquals(notes, UpdateChannel.notesOf(Arrays.asList(file("a.apk"), notes), "en"));
        assertNull("only that name", UpdateChannel.notesOf(List.of(file("old-release-notes.txt")), "en"));
        assertNull("none at all", UpdateChannel.notesOf(List.of(file("status-widget-v2.13.0.apk")), "en"));
    }

    @Test
    public void theNotesAreTheAppsLanguagesWhereTheReleaseHasThem() {
        YandexDisk.Item english = file("release-notes.txt");
        YandexDisk.Item russian = file("release-notes-ru.txt");
        List<YandexDisk.Item> both = Arrays.asList(english, russian);
        assertEquals(russian, UpdateChannel.notesOf(both, "ru"));
        assertEquals("a language with no notes of its own: the default ones", english,
                UpdateChannel.notesOf(both, "de"));
        assertEquals(english, UpdateChannel.notesOf(both, "en"));
        assertNull("only a language's: not for another language", UpdateChannel.notesOf(List.of(russian), "en"));
        YandexDisk.Item upper = file("Release-Notes-RU.txt");
        assertEquals("case aside", upper, UpdateChannel.notesOf(Arrays.asList(english, upper), "ru"));
    }

    private static YandexDisk.Item dir(String name) {
        return new YandexDisk.Item(name, "/" + name, true, 0, null);
    }

    private static YandexDisk.Item file(String name) {
        return new YandexDisk.Item(name, "/x/" + name, false, 1, null);
    }
}
