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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.concurrent.TimeUnit;

import dezz.status.widget.update.UpdateInstall;
import dezz.status.widget.update.UpdateState;
import dezz.status.widget.update.UpdateWords;
import dezz.status.widget.update.Updater;

/**
 * The update offer over whatever app is in front — the widget runs as a service on a head unit whose settings
 * nobody opens, so this card is where a new release reaches the driver: the release, what installing it
 * changes, its notes, and «Установить», «Позже», «Пропустить версию». It follows the download and goes when the
 * system's installer takes over (whose dialog it must not cover), when the offer is answered anywhere, and —
 * asked nothing — after {@link #IGNORED_MS} untouched, which counts as «Позже».
 *
 * <p>It comes up only while the car is not moving ({@link GnssProvider#moving}): a card that asks the driver
 * to decide something does not open over the map at speed. Whether a release is offered at all is the
 * {@link Updater}'s word; the card renders its state and acts through it. Created and destroyed with the
 * widget's service, main thread.
 */
final class UpdatePrompt implements Updater.Listener, GnssProvider.Listener {

    private static final String TAG = "UpdatePrompt";
    /** An offer left untouched this long is put off, as «Позже» would. */
    private static final long IGNORED_MS = TimeUnit.MINUTES.toMillis(1);
    /**
     * While an offer waits for the car to stop, how often it looks again — and how long it first waits for the
     * location feed it has just asked for to deliver a fix, so a car already moving is not taken for a
     * standing one.
     */
    private static final long WAIT_STEP_MS = 5_000;

    private final Context service;
    private final GnssProvider gnss;
    private final Updater updater;
    private final WindowManager wm;
    /** Its own handler, so {@link #stop} may clear it whole. */
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean stopped;
    /** The location feed is asked for: an offer waits for the car to stop. */
    private boolean watchingMotion;

    @Nullable private View root;
    @Nullable private WindowManager.LayoutParams lp;
    private TextView title, detail, notes;
    private ImageView mark;
    private LinearProgressIndicator progress;
    private MaxHeightScrollView notesScroll;
    private View actions;
    private MaterialButton first, second, skip;

    UpdatePrompt(@NonNull Context service, @NonNull GnssProvider gnss) {
        this.service = service;
        this.gnss = gnss;
        this.updater = Updater.get(service);
        this.wm = service.getSystemService(WindowManager.class);
    }

    void start() {
        updater.addListener(this);
        onUpdateState(updater.state());
    }

    /** Before the location feed is destroyed: this takes its own need back. */
    void stop() {
        stopped = true;
        updater.removeListener(this);
        main.removeCallbacksAndMessages(null);
        watchMotion(false);
        dismiss();
    }

    @Override
    public void onUpdateState(UpdateState s) {
        main.removeCallbacks(lookAgain);
        if (root != null) {
            if (keepsCard(s)) {
                bind(s);
                refit();
            } else {
                dismiss();
            }
            return;
        }
        boolean wanted = wantsCard(s);
        watchMotion(wanted);
        if (wanted) main.postDelayed(lookAgain, WAIT_STEP_MS);
    }

    /** An offer waits for an answer and no card is up for it. */
    private static boolean wantsCard(UpdateState s) {
        return s.phase == UpdateState.Phase.IDLE && s.offerWanted(System.currentTimeMillis());
    }

    private final Runnable lookAgain = this::lookAgain;

    private void lookAgain() {
        UpdateState s = updater.state();
        if (stopped || root != null || !wantsCard(s)) {
            watchMotion(false);
            return;
        }
        if (gnss.moving()) {
            main.postDelayed(lookAgain, WAIT_STEP_MS);
        } else {
            watchMotion(false);
            show(s);
        }
    }

    private void watchMotion(boolean on) {
        if (on == watchingMotion) return;
        watchingMotion = on;
        gnss.setNeeds(this, on ? GnssProvider.NEED_LOCATION : GnssProvider.NEED_NONE);
    }

    /** Is a card already up still the place for {@code s}: an offer to answer, or one under way. */
    private static boolean keepsCard(UpdateState s) {
        return switch (s.phase) {
            case DOWNLOADING -> true;
            // Gone once the installer's own confirmation is up: the card must not lie over its buttons.
            case INSTALLING -> !s.awaitingConfirmation;
            case CHECKING, IDLE -> s.offerWanted(System.currentTimeMillis());
        };
    }

    // ── The window ──────────────────────────────────────────────────────────────

    private void show(UpdateState s) {
        build();
        bind(s);
        // NOT_FOCUSABLE: the app in front keeps the keys and Back, and touches outside the card go to it.
        // HARDWARE_ACCELERATED: a window added from a service does not get it by default.
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        fit();
        try {
            wm.addView(root, lp);
            Log.i(TAG, "Offering " + (s.offer != null ? s.offer.version : "?") + " over the screen");
        } catch (RuntimeException e) {
            Log.w(TAG, "Update card refused", e);
            root = null;
            main.removeCallbacks(ignored);   // a card never seen was not ignored
        }
    }

    /**
     * Size the card for what it says now: as wide as it may be, its notes given what the screen leaves the
     * rest of it. Again at every change of the words — a failure's two lines must not push the card off the
     * screen it was fitted to with one.
     */
    @SuppressWarnings("deprecation")   // getDefaultDisplay: the API 28-29 way, and still right on later ones
    private void fit() {
        DisplayMetrics screen = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(screen);
        int margin = px(R.dimen.updateCardMargin);
        int room = px(R.dimen.updateCardShadowRoom);
        int width = Math.min(px(R.dimen.updateCardWidth) + 2 * room, screen.widthPixels - 2 * margin);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int notesVisibility = notesScroll.getVisibility();
        notesScroll.setVisibility(View.GONE);
        root.measure(widthSpec, unbounded);
        notesScroll.setVisibility(notesVisibility);
        int notesRoom = screen.heightPixels - 2 * margin - root.getMeasuredHeight()
                - ((ViewGroup.MarginLayoutParams) notesScroll.getLayoutParams()).topMargin;
        notesScroll.setMaxHeight(Math.max(0, notesRoom));
        lp.width = width;
    }

    /** {@link #fit} a card that is up, resizing its window to match. */
    private void refit() {
        fit();
        try {
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
            // the window went with the permission; the next offer is placed afresh
        }
    }

    private void dismiss() {
        main.removeCallbacks(ignored);
        if (root == null) return;
        View r = root;
        root = null;
        try {
            wm.removeView(r);
        } catch (RuntimeException ignored) {
            // already gone with its window
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void build() {
        // The app's theme over the service context, afresh at every show: a day/night flip is picked up by the
        // next card.
        Context themed = new ContextThemeWrapper(service, R.style.Theme_StatusWidget);
        FrameLayout frame = new FrameLayout(themed) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) restartIgnoredClock();
                return super.dispatchTouchEvent(e);
            }
        };
        int room = px(R.dimen.updateCardShadowRoom);
        frame.setPadding(room, room, room, room);
        LayoutInflater.from(themed).inflate(R.layout.overlay_update, frame, true);
        title = frame.findViewById(R.id.updateTitle);
        detail = frame.findViewById(R.id.updateDetail);
        notes = frame.findViewById(R.id.updateNotes);
        notesScroll = frame.findViewById(R.id.updateNotesScroll);
        mark = frame.findViewById(R.id.updateMark);
        progress = frame.findViewById(R.id.updateProgress);
        actions = frame.findViewById(R.id.updateActions);
        first = frame.findViewById(R.id.updateFirst);
        second = frame.findViewById(R.id.updateSecond);
        skip = frame.findViewById(R.id.updateSkip);
        frame.findViewById(R.id.updateClose).setOnClickListener(v -> updater.remindLater());
        skip.setOnClickListener(v -> updater.skip());
        root = frame;
    }

    /** The card's words and answers for {@code s}. */
    private void bind(UpdateState s) {
        long now = System.currentTimeMillis();
        mark.setImageTintList(ColorStateList.valueOf(UpdateWords.tone(s).color(root.getContext())));
        title.setText(UpdateWords.title(service, s));
        boolean idle = s.phase == UpdateState.Phase.IDLE;
        boolean failed = s.problemToTell() != UpdateState.Problem.NONE;
        String change = UpdateWords.change(service, s);
        detail.setText(idle && !failed && change != null ? change : UpdateWords.detail(service, s));

        progress.setVisibility(s.phase == UpdateState.Phase.DOWNLOADING ? View.VISIBLE : View.GONE);
        progress.setProgressCompat(s.progress, true);   // -1, size unknown, reads as 0

        String text = s.offer != null ? s.offer.notes : null;
        notesScroll.setVisibility(text != null ? View.VISIBLE : View.GONE);
        notes.setText(text);

        // The installing answer leads, filled; stopping a download or putting the offer off is the outlined
        // second — the two never come together (one is a download's, the other an idle offer's).
        UpdateState.Action action = s.action();
        answer(first, action.movesOn() ? UpdateWords.action(service, action) : null,
                () -> UpdateInstall.perform(service, action));
        if (action == UpdateState.Action.CANCEL) {
            answer(second, UpdateWords.action(service, action), updater::cancel);
        } else {
            answer(second, s.canPutOff(now) ? service.getString(R.string.update_action_later) : null,
                    updater::remindLater);
        }
        boolean any = first.getVisibility() == View.VISIBLE || second.getVisibility() == View.VISIBLE;
        actions.setVisibility(any ? View.VISIBLE : View.GONE);
        skip.setVisibility(s.canSkip() ? View.VISIBLE : View.GONE);
        // Only an offer waiting for an answer is put off by being ignored; one under way stays as long as it runs.
        if (idle) restartIgnoredClock();
        else main.removeCallbacks(ignored);
    }

    /** Show {@code b} saying {@code text} and doing {@code action}, or hide it when there is no text. */
    private static void answer(MaterialButton b, @Nullable CharSequence text, Runnable action) {
        b.setVisibility(text != null ? View.VISIBLE : View.GONE);
        b.setText(text);
        b.setOnClickListener(v -> action.run());
    }

    private final Runnable ignored = this::ignored;

    private void ignored() {
        Log.i(TAG, "Update card ignored — put off");
        updater.remindLater();
    }

    private void restartIgnoredClock() {
        main.removeCallbacks(ignored);
        if (root != null && updater.state().phase == UpdateState.Phase.IDLE) main.postDelayed(ignored, IGNORED_MS);
    }

    private int px(int dimen) {
        return service.getResources().getDimensionPixelSize(dimen);
    }
}
