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

import androidx.annotation.Nullable;

/**
 * Everything the updater knows at one moment, as one immutable answer: the settings section and the card
 * over other apps render this and decide nothing of their own — whether a release is put to the
 * person is {@link #offerWanted}, the policy's word.
 */
public final class UpdateState {

    public enum Phase {
        /** Nothing under way. */
        IDLE,
        /** Reading the channel. */
        CHECKING,
        /** Downloading and verifying the package. */
        DOWNLOADING,
        /** The package went to the system's installer, which has not answered yet. */
        INSTALLING
    }

    /** What the last action failed on; the wording is {@code UpdateWords}'. */
    public enum Problem {
        NONE,
        /** The channel could not be read (no network, the disk refused). */
        CHECK_FAILED,
        /** The package could not be downloaded. */
        DOWNLOAD_FAILED,
        /** The download is not the file the channel lists (size or digest). */
        CORRUPT,
        /** The package is not this app, or not the version its folder is named for — a publishing mistake. */
        WRONG_PACKAGE,
        /** The system's installer refused it ({@link #problemDetail} is its reason). */
        INSTALL_FAILED
    }

    /** The one action of the moment, which every surface offers first (worded by {@code UpdateWords}). */
    public enum Action {
        NONE,
        /** Install the offered release. */
        INSTALL,
        /** Install it again after the last attempt failed. */
        RETRY,
        /** Open the installer's waiting confirmation again ({@link Updater#confirmInstall}). */
        CONFIRM,
        /** Stop the download ({@link Updater#cancel}). */
        CANCEL;

        /** Does it move the installation on — the answer every surface leads with, filled — rather than stop
         *  it, or be no answer at all. */
        public boolean movesOn() {
            return this == INSTALL || this == RETRY || this == CONFIRM;
        }
    }

    /** False when this build names no channel or its own version does not read: nothing is ever offered. */
    public final boolean available;
    @Nullable public final ReleaseVersion installed;
    /** The newest release above the installed one the channel was last seen to hold. */
    @Nullable public final UpdateOffer offer;
    public final Phase phase;
    /** Percent while downloading; -1 while the size is unknown. */
    public final int progress;
    public final Problem problem;
    /** The failure's own words (the installer's message, the network's), when it had any — shown only for the
     *  installer's refusal ({@code UpdateWords}); every one of them is in the log. */
    @Nullable public final String problemDetail;
    /** The installer's confirmation was opened and waits for the person ({@link Updater#confirmInstall}). */
    public final boolean awaitingConfirmation;
    /** Wall-clock time of the last check that read the channel; 0 = never. */
    public final long lastCheckMs;
    public final boolean autoCheck;
    @Nullable final String skipped;
    @Nullable final String snoozed;
    final long snoozedUntilMs;

    UpdateState(boolean available, @Nullable ReleaseVersion installed, @Nullable UpdateOffer offer, Phase phase,
                int progress, Problem problem, @Nullable String problemDetail, boolean awaitingConfirmation,
                long lastCheckMs, boolean autoCheck, @Nullable String skipped, @Nullable String snoozed,
                long snoozedUntilMs) {
        this.available = available;
        this.installed = installed;
        this.offer = offer;
        this.phase = phase;
        this.progress = progress;
        this.problem = problem;
        this.problemDetail = problemDetail;
        this.awaitingConfirmation = awaitingConfirmation;
        this.lastCheckMs = lastCheckMs;
        this.autoCheck = autoCheck;
        this.skipped = skipped;
        this.snoozed = snoozed;
        this.snoozedUntilMs = snoozedUntilMs;
    }

    /**
     * Does the offered release stand: there is one and the person has not skipped it. It stands until it is
     * installed or skipped — the settings' «Обновления» shows it, and the screen's title marks it, all that
     * time; putting it off only quiets the reminders ({@link #offerWanted}).
     */
    public boolean offerStanding() {
        return offer != null && !offerSkipped();
    }

    /** Is the offered release to be put to the person now — by the reminder, the card over other apps: it
     *  stands and is not put off. */
    public boolean offerWanted(long nowMs) {
        return UpdatePolicy.offerWanted(offer, skipped, snoozed, snoozedUntilMs, nowMs);
    }

    /**
     * The failure to tell the person. A check that could not reach the channel says nothing while a release it
     * found earlier is still offered — that offer stands, and it is what the person can act on.
     */
    public Problem problemToTell() {
        return problem == Problem.CHECK_FAILED && offer != null ? Problem.NONE : problem;
    }

    /** The action to offer first now. */
    public Action action() {
        if (phase == Phase.IDLE && offer != null) {
            return problemToTell() != Problem.NONE ? Action.RETRY : Action.INSTALL;
        }
        if (phase == Phase.INSTALLING && awaitingConfirmation) return Action.CONFIRM;
        if (phase == Phase.DOWNLOADING) return Action.CANCEL;
        return Action.NONE;
    }

    /** A reminder's «Позже» means something now: an offer waits for an answer and is not put off already. The
     *  settings section offers no «Позже» — the release stays there until it is installed or skipped. */
    public boolean canPutOff(long nowMs) {
        return phase == Phase.IDLE && offerWanted(nowMs);
    }

    /** «Пропустить версию» means something now: an offer waits and is not skipped already. */
    public boolean canSkip() {
        return phase == Phase.IDLE && offer != null && !offerSkipped();
    }

    /** The person skipped the offered release. */
    public boolean offerSkipped() {
        return offer != null && offer.version.toString().equals(skipped);
    }

    /** The version the person skipped, whatever is offered now; null when none. */
    @Nullable
    public String skippedVersion() {
        return skipped;
    }

    /** Something is under way — a check, a download, an installation. */
    public boolean busy() {
        return phase != Phase.IDLE;
    }
}
