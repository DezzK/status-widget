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
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import dezz.status.widget.R;

/**
 * An {@link UpdateState} in words and a color — one wording for the settings section and the card over other
 * apps, so they cannot tell the person different things.
 */
public final class UpdateWords {

    private UpdateWords() {}

    /** What a state's mark says at a glance; {@link #color} paints it. */
    public enum Tone {
        /** Nothing known (never checked, no channel). */
        OFF,
        /** Nothing to do. */
        OK,
        /** Something to decide, or under way. */
        INFO,
        /** The channel could not be read. */
        WARN,
        /** Something failed that the person has to know about. */
        BAD;

        /** The tone's color in {@code themed}'s theme: the app's accent for INFO, the status colors otherwise. */
        @ColorInt
        public int color(Context themed) {
            return switch (this) {
                case OK -> themed.getColor(R.color.status_ok);
                case WARN -> themed.getColor(R.color.status_warning);
                case BAD -> themed.getColor(R.color.status_error);
                case OFF -> themed.getColor(R.color.status_off);
                case INFO -> {
                    TypedValue v = new TypedValue();
                    themed.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, v, true);
                    yield v.data;
                }
            };
        }
    }

    /** The state's headline («Доступна версия 3.16.0», «Установлена последняя версия»). Whether a release was
     *  put off does not change it: that only quiets the reminders. */
    public static String title(Context ctx, UpdateState s) {
        String offered = s.offer != null ? s.offer.version.toString() : "";
        if (!s.available) return ctx.getString(R.string.update_unavailable);
        switch (s.phase) {
            case CHECKING:
                return ctx.getString(R.string.update_checking);
            case DOWNLOADING:
                return ctx.getString(R.string.update_downloading_fmt, offered);
            case INSTALLING:
                return ctx.getString(R.string.update_installing_fmt, offered);
            default:
                break;
        }
        switch (s.problemToTell()) {
            case CHECK_FAILED:
                return ctx.getString(R.string.update_problem_check);
            case DOWNLOAD_FAILED:
                return ctx.getString(R.string.update_problem_download);
            case CORRUPT:
                return ctx.getString(R.string.update_problem_corrupt);
            case WRONG_PACKAGE:
                return ctx.getString(R.string.update_problem_wrong_package);
            case INSTALL_FAILED:
                return ctx.getString(R.string.update_problem_install);
            default:
                break;
        }
        if (s.offer == null) {
            return ctx.getString(s.lastCheckMs > 0 ? R.string.update_up_to_date : R.string.update_never_checked);
        }
        return s.offerSkipped() ? ctx.getString(R.string.update_skipped_fmt, offered)
                : ctx.getString(R.string.update_available_fmt, offered);
    }

    /** What the headline means: the failure's reason, the installer's wait, or which version is installed and
     *  when the channel was last read. */
    public static String detail(Context ctx, UpdateState s) {
        if (!s.available) return ctx.getString(R.string.update_unavailable_sub);
        if (s.phase == UpdateState.Phase.DOWNLOADING) {
            String total = s.offer != null ? size(ctx, s.offer.apkSize) : "—";
            return s.progress >= 0 ? ctx.getString(R.string.update_progress_fmt, s.progress, total) : total;
        }
        if (s.phase == UpdateState.Phase.INSTALLING) {
            return ctx.getString(s.awaitingConfirmation ? R.string.update_confirm_in_installer
                    : R.string.update_handing_to_installer);
        }
        // What went wrong, in the person's words; the technical reason is in the log. Only the installer's own
        // refusal is shown as it gave it — it names what a person can act on (another signature, no space).
        switch (s.problemToTell()) {
            case CHECK_FAILED:
            case DOWNLOAD_FAILED:
                return ctx.getString(R.string.update_problem_network_sub);
            case CORRUPT:
                return ctx.getString(R.string.update_problem_corrupt_sub);
            case WRONG_PACKAGE:
                return ctx.getString(R.string.update_problem_wrong_package_sub);
            case INSTALL_FAILED:
                if (s.problemDetail != null && !s.problemDetail.isEmpty()) return s.problemDetail;
                break;
            default:
                break;
        }
        String installed = s.installed != null ? s.installed.toString() : "—";
        if (s.offer == null) {
            // Under «the latest version is installed»: which one, and since when that is known.
            return s.lastCheckMs > 0
                    ? ctx.getString(R.string.update_current_checked_fmt, installed, when(ctx, s.lastCheckMs))
                    : ctx.getString(R.string.update_installed_fmt, installed);
        }
        if (s.offerStanding()) {
            return ctx.getString(R.string.update_installed_size_fmt, installed, size(ctx, s.offer.apkSize));
        }
        return s.lastCheckMs > 0
                ? ctx.getString(R.string.update_installed_checked_fmt, installed, when(ctx, s.lastCheckMs))
                : ctx.getString(R.string.update_installed_fmt, installed);
    }

    /** The state's color: a failure, something to decide or under way, nothing to do, nothing known. */
    public static Tone tone(UpdateState s) {
        if (!s.available) return Tone.OFF;
        if (s.busy()) return Tone.INFO;
        UpdateState.Problem problem = s.problemToTell();
        if (problem == UpdateState.Problem.CHECK_FAILED) return Tone.WARN;
        if (problem != UpdateState.Problem.NONE) return Tone.BAD;
        if (s.offerStanding()) return Tone.INFO;
        return s.offer != null || s.lastCheckMs > 0 ? Tone.OK : Tone.OFF;
    }

    /** «3.15.0 → 3.16.0 · 24 МБ»: what installing the offered release changes. */
    @Nullable
    public static String change(Context ctx, UpdateState s) {
        if (s.offer == null || s.installed == null) return null;
        return ctx.getString(R.string.update_change_fmt, s.installed.toString(), s.offer.version.toString(),
                size(ctx, s.offer.apkSize));
    }

    /** The words on the button of {@code action}; never asked for {@link UpdateState.Action#NONE}. */
    public static String action(Context ctx, UpdateState.Action action) {
        return switch (action) {
            case RETRY -> ctx.getString(R.string.update_action_retry);
            case CONFIRM -> ctx.getString(R.string.update_action_confirm);
            case CANCEL -> ctx.getString(R.string.update_action_cancel);
            default -> ctx.getString(R.string.update_action_install);
        };
    }

    /** A package's size in the device's locale («7,2 МБ»). */
    private static String size(Context ctx, long bytes) {
        return Formatter.formatShortFileSize(ctx, bytes);
    }

    /** A wall-clock moment as a date and time in the device's locale. */
    private static String when(Context ctx, long ms) {
        return DateUtils.formatDateTime(ctx, ms,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }
}
