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
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.R;

/**
 * The update channel this build reads, and the one statement of how a channel is laid out. A channel is a
 * public Yandex.Disk folder holding a folder per release, named by its version; a release folder holds this
 * app's package and, optionally, the release notes — the default ones and a language's own:
 *
 * <pre>
 * &lt;channel&gt;/
 *   v2.13.0/                        a release: its version as VERSION_NAME, the leading «v» optional
 *     status-widget-v2.13.0.apk     the package, named as the release build names it: &lt;name&gt;-&lt;version&gt;.apk,
 *                                     the version the folder's (a package of another version is not read)
 *     release-notes.txt             optional: what changed, UTF-8 plain text, shown as written — in English,
 *                                     the app's default language
 *     release-notes-ru.txt          optional: the same in a language, taken where the app runs in it
 * </pre>
 *
 * The app is offered the newest release above its installed version that holds its package; a release
 * without it (still uploading) is passed over for the next one down. Nothing else in the channel is read, so
 * old releases may stay or go.
 *
 * <p>All of it comes from the build's resources, so a cold process (the background job) configures itself:
 * {@code update_channel_folder}, the channel's public link, and {@code update_package_name}, the package's
 * name before its version, which the build gives from the same value it names its APK by.
 */
public final class UpdateChannel {

    /** The release notes' file name in a release folder, matched without regard to case: the default notes
     *  are {@code release-notes.txt}, a language's {@code release-notes-ru.txt} ({@link #notesFile}). */
    private static final String NOTES_NAME = "release-notes";
    private static final char NOTES_LANGUAGE_SEPARATOR = '-';
    private static final String NOTES_SUFFIX = ".txt";
    private static final String PACKAGE_SUFFIX = ".apk";
    /** Between a package's name and its version ({@code status-widget-v2.13.0.apk}). */
    private static final char NAME_SEPARATOR = '-';
    /** Release notes are read for a card and a settings section, not a book. */
    private static final int NOTES_MAX_BYTES = 32 * 1024;
    /** The channel's root: the shared folder itself. */
    private static final String ROOT = "/";

    /** The channel's public link. */
    final String folder;
    /** This app's package name before its version. */
    final String packageName;

    UpdateChannel(String folder, String packageName) {
        this.folder = folder;
        this.packageName = packageName;
    }

    /** This build's channel, or null when it names none (then nothing is ever offered). */
    @Nullable
    static UpdateChannel of(Context ctx) {
        String folder = ctx.getString(R.string.update_channel_folder).trim();
        String name = ctx.getString(R.string.update_package_name).trim();
        if (folder.isEmpty() || name.isEmpty()) return null;
        return new UpdateChannel(folder, name);
    }

    /**
     * The newest release above {@code installed} that the channel offers this app, or null when there is
     * none, with its notes in {@code language} where the release has them. Blocks on the network; throws when
     * the disk could not be read, which is not the same answer as «nothing newer».
     */
    @Nullable
    UpdateOffer newestAbove(ReleaseVersion installed, String language) throws IOException {
        for (YandexDisk.Item release : releasesAbove(YandexDisk.list(folder, ROOT), installed)) {
            ReleaseVersion version = ReleaseVersion.parse(release.name);
            List<YandexDisk.Item> files;
            try {
                files = YandexDisk.list(folder, release.path);
            } catch (YandexDisk.NotFound e) {
                continue;   // removed between the two listings
            }
            YandexDisk.Item apk = packageOf(files, packageName, version);
            if (apk == null) continue;
            YandexDisk.Item notes = notesOf(files, language);
            return new UpdateOffer(version, apk.path, apk.size, apk.sha256,
                    notes != null ? readNotes(notes.path) : null);
        }
        return null;
    }

    /** The release folders above {@code installed}, newest first. */
    static List<YandexDisk.Item> releasesAbove(List<YandexDisk.Item> items, ReleaseVersion installed) {
        List<YandexDisk.Item> out = new ArrayList<>();
        for (YandexDisk.Item it : items) {
            ReleaseVersion v = it.dir ? ReleaseVersion.parse(it.name) : null;
            if (v != null && v.isNewerThan(installed)) out.add(it);
        }
        out.sort(Comparator.comparing((YandexDisk.Item it) -> ReleaseVersion.parse(it.name)).reversed());
        return out;
    }

    /** The package {@code name}-{@code version}.apk in a release folder (case and the «v» aside), or null. */
    @Nullable
    static YandexDisk.Item packageOf(List<YandexDisk.Item> files, String name, ReleaseVersion version) {
        String prefix = (name + NAME_SEPARATOR).toLowerCase(Locale.ROOT);
        for (YandexDisk.Item it : files) {
            String file = it.name.toLowerCase(Locale.ROOT);
            if (it.dir || !file.startsWith(prefix) || !file.endsWith(PACKAGE_SUFFIX)) continue;
            String rest = file.substring(prefix.length(), file.length() - PACKAGE_SUFFIX.length());
            // A debug build left beside the release («status-widget-v2.13.0-debug.apk») reads as the
            // pre-release «2.13.0-debug», which is not the folder's version, so it is not taken.
            if (version.equals(ReleaseVersion.parse(rest))) return it;
        }
        return null;
    }

    /** The notes' file name: {@code language}'s, or the default notes' for null. */
    static String notesFile(@Nullable String language) {
        return NOTES_NAME + (language != null ? NOTES_LANGUAGE_SEPARATOR + language : "") + NOTES_SUFFIX;
    }

    /** The release notes in a release folder: {@code language}'s, else the default ones; null when it has none. */
    @Nullable
    static YandexDisk.Item notesOf(List<YandexDisk.Item> files, String language) {
        YandexDisk.Item own = fileNamed(files, notesFile(language));
        return own != null ? own : fileNamed(files, notesFile(null));
    }

    @Nullable
    private static YandexDisk.Item fileNamed(List<YandexDisk.Item> files, String name) {
        for (YandexDisk.Item it : files) {
            if (!it.dir && it.name.equalsIgnoreCase(name)) return it;
        }
        return null;
    }

    /** A release's notes; null when they cannot be read — the release is offered all the same. */
    @Nullable
    private String readNotes(String path) {
        try {
            HttpURLConnection conn = Http.open(YandexDisk.downloadUrl(folder, path));
            try (InputStream in = conn.getInputStream()) {
                String text = Http.readText(in, NOTES_MAX_BYTES).trim();
                return text.isEmpty() ? null : text;
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            Log.w(Updater.TAG, "Release notes at " + path + " unreadable", e);
            return null;
        }
    }

    /** The package's download URL, asked for right before the download (the disk's links are short-lived). */
    String downloadUrl(UpdateOffer offer) throws IOException {
        return YandexDisk.downloadUrl(folder, offer.apkPath);
    }
}
