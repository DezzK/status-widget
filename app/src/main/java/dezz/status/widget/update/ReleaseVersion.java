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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's version as the build names it ({@code VERSION_NAME}, e.g. {@code v3.16.0}) and as a version
 * folder of the update channel is named: {@code major.minor[.patch]}, an optional leading {@code v}, an
 * optional pre-release suffix after a hyphen ({@code 3.16.0-beta1}). The one reader of that form on the
 * device, so the installed version and a folder's are compared by the same rule.
 *
 * <p>Ordered as semantic versions are: by the numbers, then a release above its pre-releases, then two
 * pre-releases by their suffix. It answers «is this folder newer» — whether Android accepts the package as an
 * upgrade is the package's own versionCode, the installer's to judge.
 */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {

    private static final Pattern FORM =
            Pattern.compile("v?(\\d{1,6})\\.(\\d{1,6})(?:\\.(\\d{1,6}))?(?:-([0-9A-Za-z][0-9A-Za-z.\\-]*))?");

    public final int major;
    public final int minor;
    public final int patch;
    /** The pre-release suffix, or null for a release. */
    @Nullable public final String suffix;

    private ReleaseVersion(int major, int minor, int patch, @Nullable String suffix) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.suffix = suffix;
    }

    /** {@code text} read as a version, or null when it is not one (a folder of something else). */
    @Nullable
    public static ReleaseVersion parse(@Nullable String text) {
        if (text == null) return null;
        Matcher m = FORM.matcher(text.trim());
        if (!m.matches()) return null;
        return new ReleaseVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : 0, m.group(4));
    }

    @Override
    public int compareTo(ReleaseVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (suffix == null) return o.suffix == null ? 0 : 1;
        return o.suffix == null ? -1 : suffix.compareTo(o.suffix);
    }

    public boolean isNewerThan(ReleaseVersion o) {
        return compareTo(o) > 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReleaseVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, suffix);
    }

    /** The form shown to a person and stored: no {@code v}, the patch always written ({@code 3.16.0}). */
    @NonNull
    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (suffix != null ? "-" + suffix : "");
    }
}
