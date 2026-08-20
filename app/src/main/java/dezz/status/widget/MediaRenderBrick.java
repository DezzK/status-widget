/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * The media brick — the only composite one. Its root is a container holding a source line, a title
 * line and a progress bar, and it feeds itself from whatever {@code MediaSession} is currently
 * playing.
 *
 * <p>Most of the care in here is about NOT relayouting: players republish their playback state and
 * metadata continuously (once a second is common), so every setter on the hot path has to be a
 * no-op when the value did not actually change, and the parts that come and go — the duration, the
 * progress bar — only collapse on a real track change rather than on a transient blip.
 */
final class MediaRenderBrick extends RenderBrick {

    private static final String TAG = "MediaRenderBrick";
    private static final long MEDIA_PROGRESS_TICK_MS = 250L;
    /** Gap between the play/pause indicator and its line, as a fraction of that line's text size. */
    private static final float STATE_ICON_GAP_RATIO = 0.25f;

    private final Preferences.MediaBrickPrefs p;

    private LinearLayout container;
    private LinearLayout sourceRow;
    private LinearLayout titleRow;
    private MarqueeOutlineTextView appText;
    private MarqueeOutlineTextView titleText;
    private OutlineTextView durationText;
    private MediaStateIconView stateIcon;
    private MediaProgressBar progressBar;

    MediaRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.MEDIA);
        this.p = host.prefs().media;
    }

    @Override
    void bind(@NonNull OverlayStatusWidgetBinding binding) {
        container = binding.mediaContainer;
        sourceRow = binding.mediaSourceRow;
        titleRow = binding.mediaTitleRow;
        appText = binding.mediaAppText;
        titleText = binding.mediaTitleText;
        durationText = binding.mediaDurationText;
        stateIcon = binding.mediaStateIcon;
        progressBar = binding.mediaProgressBar;
        // Fresh views, fresh state: the progress bar starts out gone in the layout.
        progressBarShown = false;
    }

    @NonNull
    @Override
    View view() {
        return container;
    }

    @Override
    void applySettings() {
        if (container == null) return;
        applyMediaBrickSettings();
    }

    /** Nothing renders without a session to render, so the brick gates itself on one. */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type) && pickActiveMediaController() != null;
    }

    @Override
    void onWillRender() {
        // Deliberately unconditional: the brick's children keep whatever hideMediaBrick() left
        // behind, so a container coming back to VISIBLE would otherwise come up empty until the
        // next player callback.
        updateMediaInfo();
    }

    @Override
    int minHeight() {
        return mediaBrickHeight();
    }

    @Override
    void syncSource(boolean active) {
        if (active && Permissions.isNotificationAccessGranted(host.context())) {
            enableMediaTracking();
        } else {
            disableMediaTracking();
            if (container != null) container.setVisibility(View.GONE);
        }
    }

    @Override
    void onDestroy() {
        stopMediaProgressTicker();
        disableMediaTracking();
    }

    private static boolean isUnknownArtistPlaceholder(@Nullable String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        return trimmed.equalsIgnoreCase("unknown artist")
                || trimmed.equalsIgnoreCase("неизвестный исполнитель");
    }

    /** Last path segment of a media URI, without its extension — a last-resort track title. */
    @Nullable
    private static String filenameFromUri(@Nullable String raw) {
        if (isEmpty(raw)) return null;
        String last = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(raw);
            last = uri.getLastPathSegment();
        } catch (Exception ignored) {
        }
        if (isEmpty(last)) {
            int slash = raw.lastIndexOf('/');
            last = slash >= 0 ? raw.substring(slash + 1) : raw;
        }
        if (isEmpty(last)) return null;
        int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        last = last.replace('_', ' ').trim();
        return isEmpty(last) ? null : last;
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    private static boolean isMediaActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_PLAYING:
                return true;
            default:
                return false;
        }
    }

    /** Write text only when it differs — {@code setText} relayouts even for identical text. */
    private static void setTextIfChanged(android.widget.TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private MediaSessionManager mediaSessionManager;

    private final List<MediaController> activeMediaControllers = new ArrayList<>();

    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            updateMediaInfo();
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            updateMediaInfo();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            this::rebindMediaControllers;


    /** Last rendered media subtitle — used to distinguish a real track change from the
     *  once-a-second metadata republishes some players emit (see updateMediaInfo). */
    @Nullable
    private String lastMediaSubtitle = null;


    /** Intended progress-bar visibility. Ours, not the view's: {@code View.getVisibility()} is not
     *  authoritative while a transition owns the view — see {@link #setProgressBarShown}. Reset
     *  whenever the overlay is re-inflated, since the XML default is {@code gone}. */
    private boolean progressBarShown = false;


    private void applyMediaBrickSettings() {
        int textColor = ContextCompat.getColor(host.themed(), R.color.text_primary);

        // Source line: independent font, opacity, outline.
        Typeface sourceTypeface = Fonts.resolve(host.context(), p.sourceFontFamily.get(),
                p.sourceFontBold.get(), p.sourceFontItalic.get());
        appText.setOutlineColor(outlineColor(host.themed(), p.sourceOutlineAlpha.get()));
        appText.setOutlineWidth(p.sourceOutlineWidth.get());
        appText.setTextColor(textColor);
        appText.setTypeface(sourceTypeface);
        appText.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.sourceFontSize.get());
        appText.setAlpha(p.sourceContentAlpha.get() / 255f);

        // Title line: existing media.* font + opacity + outline (TextBrickPrefs inherited).
        Typeface titleTypeface = Fonts.resolve(host.context(), p.fontFamily.get(),
                p.fontBold.get(), p.fontItalic.get());
        titleText.setOutlineColor(outlineColor(host.themed(), p.outlineAlpha.get()));
        titleText.setOutlineWidth(p.outlineWidth.get());
        titleText.setTextColor(textColor);
        titleText.setTypeface(titleTypeface);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.fontSize.get());
        titleText.setAlpha(p.contentAlpha.get() / 255f);

        // Source line is always static + ellipsized; only the title scrolls. Source is short
        // and a constant moving marquee on it would be more distracting than helpful.
        appText.setMarqueeEnabled(false);
        titleText.setMarqueeEnabled(p.marqueeEnabled.get());

        applyMediaStateIcon(textColor);

        // Duration text — independent font size / alpha / outline so the user can dial it down
        // (typically the duration is rendered smaller and dimmer than the track subtitle).
        durationText.setTypeface(titleTypeface);
        durationText.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.durationFontSize.get());
        durationText.setTextColor(textColor);
        durationText.setOutlineColor(outlineColor(host.themed(), p.durationOutlineAlpha.get()));
        durationText.setOutlineWidth(p.durationOutlineWidth.get());
        durationText.setAlpha(p.durationContentAlpha.get() / 255f);

        applyHorizontalMargins(container, p.marginStart.get(), p.marginEnd.get());
        container.setTranslationY(p.adjustY.get());
        // Container alpha back to full — per-line alpha is set above so the two values don't
        // multiply through the parent.
        container.setAlpha(1f);
        applyMediaMaxWidth(appText);
        applyMediaMaxWidth(titleText);
        // Alignment applies to the two ROWS — they, not the text views, are the children of the
        // vertical container, and layout_gravity on a child of a horizontal LinearLayout only
        // ever moves it vertically.
        applyMediaChildAlignment(sourceRow, p.sourceAlignment.get());
        applyMediaChildAlignment(titleRow, p.alignment.get());
        // Vertical gap between the two lines, applied as the title row's top margin.
        LinearLayout.LayoutParams titleLp =
                (LinearLayout.LayoutParams) titleRow.getLayoutParams();
        titleLp.topMargin = p.lineGap.get();
        titleRow.setLayoutParams(titleLp);
    }


    /**
     * Playback-state indicator. It lives at the head of the source row — "▶ Spotify" reads as one
     * statement — but the source line is optional, so when it's off the icon is re-parented to the
     * head of the title row instead of vanishing with its host. Either way it takes the size,
     * outline and opacity of the line it sits on, so it scales with that line's font-size slider
     * and flips colour with the widget theme like the text around it.
     */
    private void applyMediaStateIcon(int textColor) {
        boolean onSourceRow = p.showSource.get();
        LinearLayout hostRow = onSourceRow ? sourceRow : titleRow;
        ViewGroup parent = (ViewGroup) stateIcon.getParent();
        if (parent != hostRow) {
            if (parent != null) parent.removeView(stateIcon);
            hostRow.addView(stateIcon, 0);
        }

        int fontSize = onSourceRow ? p.sourceFontSize.get() : p.fontSize.get();
        int outlineAlpha = onSourceRow
                ? p.sourceOutlineAlpha.get() : p.outlineAlpha.get();
        int outlineWidth = onSourceRow
                ? p.sourceOutlineWidth.get() : p.outlineWidth.get();
        int contentAlpha = onSourceRow
                ? p.sourceContentAlpha.get() : p.contentAlpha.get();
        stateIcon.setTextSizePx(fontSize);
        stateIcon.setIconColor(textColor);
        stateIcon.setOutlineColor(outlineColor(host.themed(), outlineAlpha));
        stateIcon.setOutlineWidth(outlineWidth);
        stateIcon.setAlpha(contentAlpha / 255f);

        // Gap to the text scales with that text too — a fixed one would glue the icon to a 60px
        // source line and strand it next to a 12px one.
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) stateIcon.getLayoutParams();
        int gap = Math.round(fontSize * STATE_ICON_GAP_RATIO);
        if (lp.getMarginEnd() != gap) {
            lp.setMarginEnd(gap);
            stateIcon.setLayoutParams(lp);
        }

        // Switching the indicator off has to be honored here too, not only in updateMediaInfo:
        // this is the only media code that runs when there is no active session, so a stale
        // VISIBLE icon would otherwise be impossible to turn off until something played again.
        // Only the off-direction is applied — turning it back on stays with updateMediaInfo,
        // which additionally requires the line hosting the icon to actually carry text.
        if (!p.showPlaybackState.get()) {
            stateIcon.setVisibility(View.GONE);
        }
    }


    /**
     * Horizontal alignment of a single line within the vertical media container.
     * Container is wrap_content (sized to the wider of the two children), so the narrower
     * child shifts within that band via its own {@code layout_gravity}.
     */
    private static void applyMediaChildAlignment(View view, int alignment) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        int gravity;
        switch (alignment) {
            case 1: gravity = Gravity.CENTER_HORIZONTAL; break;
            case 2: gravity = Gravity.END; break;
            default: gravity = Gravity.START; break;
        }
        lp.gravity = gravity;
        view.setLayoutParams(lp);
    }


    private void applyMediaMaxWidth(MarqueeOutlineTextView view) {
        // The view itself toggles between WRAP_CONTENT (text fits) and a fixed maxWidth
        // (overflow + scrolling). All we need here is to tell it the upper bound.
        view.setMaxWidth(p.maxWidth.get());
    }


    /**
     * The media brick's height floor, modelled on the real layout: two rows — each as tall as the
     * tallest view it holds — plus the progress bar.
     *
     * <p>Every term comes from the preference rather than from what is on screen right now: the
     * floor's whole job is to hold the row still while the brick's own parts come and go (a live
     * stream with no duration, the gap after a track change, a session with no app label). A
     * brick that stays 5sp taller than its content for a radio station is the intended trade —
     * the same one the floor already makes for bricks hidden by the per-app rule.
     */
    private int mediaBrickHeight() {
        int titleRow = TextRenderBrick.lineHeight(titleText, p.fontSize.get());
        if (p.showDuration.get()) {
            titleRow = Math.max(titleRow, TextRenderBrick.lineHeight(durationText,
                    p.durationFontSize.get()));
        }
        int height;
        if (p.showSource.get()) {
            int sourceRow = TextRenderBrick.lineHeight(appText, p.sourceFontSize.get());
            if (p.showPlaybackState.get()) {
                // The indicator rides the source line and takes that line's metrics.
                sourceRow = Math.max(sourceRow, MediaStateIconView.heightFor(
                        p.sourceFontSize.get(), p.sourceOutlineWidth.get()));
            }
            height = sourceRow + titleRow;
        } else {
            // With the source line off the indicator is re-parented onto the title row.
            if (p.showPlaybackState.get()) {
                titleRow = Math.max(titleRow, MediaStateIconView.heightFor(
                        p.fontSize.get(), p.outlineWidth.get()));
            }
            height = titleRow;
        }
        // The title row's top margin is applied unconditionally (applyMediaBrickSettings) and the
        // row is never hidden, so LinearLayout counts it even with the source line off.
        height += p.lineGap.get();
        if (p.progressBarEnabled.get()) {
            height += progressBarExtent();
        }
        return height;
    }


    /** Progress bar height plus its top margin, read from the layout so the sp values stay there. */
    private int progressBarExtent() {
        ViewGroup.LayoutParams lp = progressBar.getLayoutParams();
        if (lp == null || lp.height < 0) return 0;   // WRAP_CONTENT / MATCH_PARENT: nothing to add
        int extent = lp.height;
        if (lp instanceof LinearLayout.LayoutParams) {
            extent += ((LinearLayout.LayoutParams) lp).topMargin;
        }
        return extent;
    }


    private void enableMediaTracking() {
        if (mediaSessionManager != null) return;
        mediaSessionManager = (MediaSessionManager) host.context().getSystemService(android.content.Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) return;
        ComponentName component = new ComponentName(host.context(), MediaNotificationListener.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, host.handler());
            rebindMediaControllers(mediaSessionManager.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not granted; media tracking disabled", e);
            mediaSessionManager = null;
        }
    }


    private void disableMediaTracking() {
        if (mediaSessionManager == null) return;
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
        } catch (Exception ignored) {
        }
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        mediaSessionManager = null;
    }


    private void rebindMediaControllers(@Nullable List<MediaController> controllers) {
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                activeMediaControllers.add(c);
                c.registerCallback(mediaControllerCallback, host.handler());
            }
        }
        updateMediaInfo();
    }


    @Nullable
    private MediaController pickActiveMediaController() {
        // Prefer a controller that is currently playing. If none is playing, fall back to any
        // controller in a transient "media is loaded and the user is doing something with it"
        // state — paused, buffering, fast-forwarding, rewinding, skipping. Keeping the brick
        // visible across these short-lived transitions avoids a VISIBLE→GONE→VISIBLE blink
        // (which would re-layout the title text from zero size and reset the marquee scroll)
        // every time the user seeks or the player briefly buffers.
        MediaController fallback = null;
        for (MediaController c : activeMediaControllers) {
            PlaybackState s = c.getPlaybackState();
            if (s == null) continue;
            int state = s.getState();
            if (state == PlaybackState.STATE_PLAYING) {
                return c;
            }
            if (fallback == null && isMediaActiveState(state)) {
                fallback = c;
            }
        }
        return fallback;
    }


    /**
     * Nothing to show — hide the brick and reset the children whose visibility is otherwise only
     * decided on the happy path below. Leaving them at their inflate defaults (row and icon both
     * VISIBLE, the icon drawing a play triangle because setPaused has never run) is what let a
     * phantom indicator paint whenever something else drove the container VISIBLE.
     */
    private void hideMediaBrick() {
        container.setVisibility(View.GONE);
        sourceRow.setVisibility(View.GONE);
        stateIcon.setVisibility(View.GONE);
        setProgressBarShown(false);
        stopMediaProgressTicker();
    }


    private void updateMediaInfo() {
        if (container == null) return;
        if (!host.currentOrder().contains(type) || host.isBrickHiddenByApp(type)) {
            hideMediaBrick();
            return;
        }
        MediaController playing = pickActiveMediaController();
        if (playing == null) {
            hideMediaBrick();
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        String title = pickMediaTitle(metadata);
        String artist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (isUnknownArtistPlaceholder(artist)) {
            // Some players (notably stock Android Music) fill the artist field with a literal
            // "Unknown artist" / "Неизвестный исполнитель" string when the tag is missing.
            // Treat that as no artist so the subtitle falls back to the title alone.
            artist = null;
        }
        String subtitle;
        boolean titleFirst = p.titleFirst.get();
        String first = titleFirst ? title : artist;
        String second = titleFirst ? artist : title;
        if (!isEmpty(first) && !isEmpty(second)) {
            subtitle = first + " — " + second;
        } else if (!isEmpty(title)) {
            subtitle = title;
        } else if (!isEmpty(artist)) {
            subtitle = artist;
        } else {
            // Something is playing but the player exposes no metadata at all — at least show a
            // placeholder so the user can see that media playback is active.
            subtitle = host.context().getString(R.string.media_unknown_track);
        }
        PlaybackState playbackState = playing.getPlaybackState();
        // Pause shape only for an actual PAUSED; transient states (buffering / seeking) keep the
        // play shape so the icon doesn't flicker every time the user scrubs.
        // Players republish PlaybackState continuously (Yandex Music every second), and
        // TextView.setText unconditionally drops its layout and requests a full re-layout even
        // for identical text. On OEM head units that per-second layout storm makes the whole
        // title row visibly jitter while the marquee scrolls — so every setter here must be
        // a no-op when the value didn't actually change (MediaStateIconView.setPaused is).
        stateIcon.setPaused(playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PAUSED);
        String sourceLabel = getAppLabel(playing.getPackageName());
        appText.setMarqueeText(sourceLabel);
        titleText.setMarqueeText(subtitle);
        // Show the source row only when the user enabled it AND there is a name to show. Some
        // head-unit system audio routes (built-in radio, a Bluetooth profile) own a media session
        // with no resolvable package/label, so the label comes back empty; a visible-but-empty row
        // would just add dead vertical space.
        boolean showSourceRow = p.showSource.get() && !isEmpty(sourceLabel);
        sourceRow.setVisibility(showSourceRow ? View.VISIBLE : View.GONE);
        // Play/pause indicator: an optional adornment (its own setting) that annotates whichever
        // line hosts it — the source line when showSource is on, the title line otherwise
        // (applyMediaStateIcon does the re-parenting). It must never float alone, so it is bound to
        // its host line having text: on the source line that means a non-empty app label (the
        // no-label head-unit sessions above would otherwise strand a lone triangle in the row), on
        // the title line the subtitle always has a fallback so it stays. This is why the icon's own
        // visibility is toggled rather than the row's — with the source line off, the row is gone
        // yet the indicator still needs to ride the title line.
        boolean iconHostHasText = p.showSource.get()
                ? !isEmpty(sourceLabel) : !isEmpty(subtitle);
        stateIcon.setVisibility(
                p.showPlaybackState.get() && iconHostHasText ? View.VISIBLE : View.GONE);

        // Duration: format ms → "M:SS" / "H:MM:SS". Hidden when the user opted out or the
        // player doesn't expose a positive duration (live streams, podcast pre-buffer).
        long durationMs = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        // Track identity: duration/progress visibility may only COLLAPSE on a real track
        // change. Players republish metadata continuously (Yandex Music: every second) and
        // the duration is transiently absent in some republishes — hiding on those blips
        // collapsed the row height once a second, which read as the whole widget "regrouping"
        // while the marquee scrolls.
        boolean trackChanged = !TextUtils.equals(subtitle, lastMediaSubtitle);
        lastMediaSubtitle = subtitle;

        if (!p.showDuration.get()) {
            durationText.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            // Leading space gives the gap between title and duration without an extra layout
            // margin pref — scales naturally with the duration font size.
            setTextIfChanged(durationText, " " + formatTrackDuration(durationMs));
            durationText.setVisibility(View.VISIBLE);
        } else if (trackChanged) {
            // New track with no usable duration (live stream) — hide for real.
            durationText.setVisibility(View.GONE);
        }
        // else: transient blip on the same track — keep the last shown value.

        // Progress bar visibility is decided here ONLY (updateMediaProgress never touches it —
        // see the comment there). Same blip-tolerant policy as the duration text.
        if (!p.progressBarEnabled.get()) {
            setProgressBarShown(false);
        } else if (durationMs > 0L) {
            setProgressBarShown(true);
        } else if (trackChanged) {
            setProgressBarShown(false);
        }

        container.setVisibility(View.VISIBLE);

        updateMediaProgress(playing);
    }


    /**
     * Best-effort extraction of a track title from the media metadata. Falls back through several
     * standard keys, then to the file name parsed out of the media URI, so we still show something
     * useful for players that don't populate {@link MediaMetadata#METADATA_KEY_TITLE}.
     */
    @Nullable
    private static String pickMediaTitle(@Nullable MediaMetadata metadata) {
        if (metadata == null) return null;
        String[] keys = {
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        };
        for (String key : keys) {
            String value = metadata.getString(key);
            if (!isEmpty(value)) return value;
        }
        String uriFilename = filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI));
        if (!isEmpty(uriFilename)) return uriFilename;
        return filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }


    /**
     * Format a positive duration in milliseconds as {@code M:SS} (under an hour) or
     * {@code H:MM:SS} (one hour or longer). Locale-independent — uses the same digit forms
     * everywhere because the duration is displayed alongside the marquee subtitle, where
     * regional digit substitutions would look out of place.
     */
    private static String formatTrackDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }


    /**
     * Single writer for the progress bar's visibility, deduplicated against our OWN field rather
     * than against {@code getVisibility()}.
     * <p>
     * The per-second player republishes must not re-run {@code setColor}, which is why the show
     * path was originally guarded by reading the view's visibility back. But a running
     * {@link android.transition.Visibility} transition rewrites those flags: when it adopts a view
     * it calls {@code setTransitionVisibility(VISIBLE)} and fades {@code transitionAlpha} to 0, so
     * the view reports VISIBLE while drawing nothing. The read-back then saw "already VISIBLE",
     * skipped {@code setVisibility}, and the bar stayed invisible until some unrelated brick
     * transition happened to clear the state — which is exactly how hiding the clock over the
     * desktop took the progress bar down with it. Owning the flag here makes the recovery
     * unconditional, like every other child of the brick.
     */
    private void setProgressBarShown(boolean shown) {
        if (progressBarShown == shown && progressBar.getVisibility()
                == (shown ? View.VISIBLE : View.GONE)) {
            return;
        }
        progressBarShown = shown;
        if (shown) {
            progressBar.setColor(
                    ContextCompat.getColor(host.themed(),
                            R.color.text_primary));
        }
        progressBar.setVisibility(shown ? View.VISIBLE : View.GONE);
    }


    /**
     * Snap the progress bar to the current playback position and arm/disarm the periodic ticker.
     * Called both from {@link #updateMediaInfo} (state/metadata flips) and from
     * {@link #mediaProgressTick} (every ~250ms while playing) to advance the bar smoothly.
     */
    private void updateMediaProgress(@Nullable MediaController playing) {
        if (container == null) return;
        // Visibility policy: this method NEVER changes the bar's visibility. Flipping
        // GONE/VISIBLE changes the media container's height and relayouts the whole brick
        // row — and players like Yandex Music republish state/metadata every second, with
        // the duration transiently missing, which turned that flip into a once-a-second
        // visible "regroup" of the row while the marquee scrolls. Visibility is decided
        // solely in updateMediaInfo (real track/state changes); here we only advance the
        // fill fraction — a pure repaint.
        if (!p.progressBarEnabled.get() || playing == null || !progressBarShown) {
            stopMediaProgressTicker();
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        long duration = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        PlaybackState state = playing.getPlaybackState();
        if (duration <= 0L || state == null) {
            // Timeline transiently unavailable (metadata republish in flight) — keep the last
            // rendered fill and let the next tick catch up rather than touching layout.
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long lastUpdate = state.getLastPositionUpdateTime();
        long basePosition = state.getPosition();
        // PlaybackState.getPosition() returns the position as of getLastPositionUpdateTime();
        // for the *current* moment we extrapolate with the reported playback speed (typically 1.0).
        long actualPosition = basePosition
                + (long) ((now - lastUpdate) * state.getPlaybackSpeed());
        if (actualPosition < 0L) actualPosition = 0L;
        if (actualPosition > duration) actualPosition = duration;

        progressBar.setProgress((float) actualPosition / (float) duration);

        if (state.getState() == PlaybackState.STATE_PLAYING) {
            // Re-arm — the new postDelayed replaces any previously queued one, idempotent.
            host.handler().removeCallbacks(mediaProgressTick);
            host.handler().postDelayed(mediaProgressTick, MEDIA_PROGRESS_TICK_MS);
        } else {
            stopMediaProgressTicker();
        }
    }


    private void stopMediaProgressTicker() {
        host.handler().removeCallbacks(mediaProgressTick);
    }


    private final Runnable mediaProgressTick = () -> updateMediaProgress(pickActiveMediaController());


    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = host.context().getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }
}
