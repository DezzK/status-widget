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

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import dezz.status.widget.databinding.SectionUpdatesBinding;
import dezz.status.widget.update.UpdateInstall;
import dezz.status.widget.update.UpdateState;
import dezz.status.widget.update.UpdateWords;
import dezz.status.widget.update.Updater;

/**
 * «Обновления» in the settings: what the update channel offers and what is under way, the person's answers —
 * install, skip this version — the release notes, and how the channel is checked; plus the mark under the
 * screen's title while a release stands, which scrolls here. The card over other apps ({@link UpdatePrompt})
 * reminds; this is where an offered release stays until it is installed or skipped — so there is no «Позже»
 * here: putting it off only quiets the card. It renders the {@link Updater}'s state and acts through it.
 */
final class UpdateSection implements Updater.Listener {

    private final Context ctx;
    private final SectionUpdatesBinding views;
    /** Under the screen's title: «Доступна версия …» while a release stands. */
    private final TextView badge;
    private final Updater updater;

    UpdateSection(@NonNull Context ctx, @NonNull SectionUpdatesBinding views, @NonNull TextView badge,
                  @NonNull Runnable scrollHere) {
        this.ctx = ctx;
        this.views = views;
        this.badge = badge;
        this.updater = Updater.get(ctx);
        badge.setOnClickListener(v -> scrollHere.run());
        views.updateSkipButton.setOnClickListener(v -> updater.skip());
        views.updateUnskipButton.setOnClickListener(v -> updater.unskip());
    }

    /** Render now and on every change, until {@link #detach}. */
    void attach() {
        updater.addListener(this);
        onUpdateState(updater.state());
    }

    void detach() {
        updater.removeListener(this);
    }

    @Override
    public void onUpdateState(UpdateState s) {
        int tone = UpdateWords.tone(s).color(ctx);
        views.updateStatusIcon.setImageTintList(ColorStateList.valueOf(tone));
        views.updateStatusTitle.setText(UpdateWords.title(ctx, s));
        views.updateStatusDetail.setText(UpdateWords.detail(ctx, s));

        views.updateProgress.setVisibility(s.phase == UpdateState.Phase.DOWNLOADING ? View.VISIBLE : View.GONE);
        views.updateProgress.setProgressCompat(s.progress, true);   // -1, size unknown, reads as 0

        // The installing answer leads, filled; the outlined second stops a download, or — with nothing to
        // answer — reads the channel now.
        UpdateState.Action action = s.action();
        views.updateActionButton.setVisibility(action.movesOn() ? View.VISIBLE : View.GONE);
        if (action.movesOn()) {
            views.updateActionButton.setText(UpdateWords.action(ctx, action));
            views.updateActionButton.setOnClickListener(v -> UpdateInstall.perform(ctx, action));
        }
        if (action == UpdateState.Action.CANCEL) {
            secondary(UpdateWords.action(ctx, action), true, v -> updater.cancel());
        } else if (action == UpdateState.Action.NONE && s.available) {
            secondary(ctx.getString(R.string.update_action_check), !s.busy(), v -> updater.checkNow());
        } else {
            views.updateSecondaryButton.setVisibility(View.GONE);
        }
        views.updateSkipButton.setVisibility(s.canSkip() ? View.VISIBLE : View.GONE);

        String notes = s.offer != null ? s.offer.notes : null;
        views.updateNotesLabel.setVisibility(notes != null ? View.VISIBLE : View.GONE);
        views.updateNotes.setVisibility(notes != null ? View.VISIBLE : View.GONE);
        views.updateNotes.setText(notes);

        // Set without its listener: the switch follows the state, it does not write it back.
        views.updateAutoCheckSwitch.setOnCheckedChangeListener(null);
        views.updateAutoCheckSwitch.setChecked(s.autoCheck);
        views.updateAutoCheckSwitch.setOnCheckedChangeListener((b, on) -> updater.setAutoCheck(on));

        String skipped = s.skippedVersion();
        views.updateSkippedRow.setVisibility(skipped != null ? View.VISIBLE : View.GONE);
        if (skipped != null) {
            views.updateSkippedText.setText(ctx.getString(R.string.update_skipped_row_fmt, skipped));
        }

        badge.setVisibility(s.offerStanding() ? View.VISIBLE : View.GONE);
        if (s.offerStanding()) {
            badge.setText(ctx.getString(R.string.update_available_fmt, s.offer.version.toString()));
        }
    }

    private void secondary(CharSequence text, boolean enabled, View.OnClickListener click) {
        views.updateSecondaryButton.setVisibility(View.VISIBLE);
        views.updateSecondaryButton.setText(text);
        views.updateSecondaryButton.setEnabled(enabled);
        views.updateSecondaryButton.setOnClickListener(click);
    }
}
