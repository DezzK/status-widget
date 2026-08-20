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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

public class WidgetService extends Service implements WidgetHost {
    private static final int WIDGET_MODE_FLOATING = 0;
    private static final int WIDGET_MODE_STATUS_BAR = 1;

    private static final int OVERLAY_FADE_DURATION_MS = 500;
    /**
     * Duration of the combined Fade + ChangeBounds transition that handles per-brick
     * visibility flips. See {@link #beginVisibilityTransition} for the "window-buffer"
     * trick that makes this transition stay inside a stable window rectangle.
     */
    private static final int BRICK_TRANSITION_DURATION_MS = 450;
    /**
     * Duration of {@link android.animation.LayoutTransition#CHANGING} animations that fire
     * when a child changes its own size (clock minute, date, media track, icon swap). Shorter
     * than visibility flips because the user sees small frequent updates as snappy when
     * animated under ~300ms; longer feels sluggish for tiny shifts.
     */
    private static final int CONTENT_CHANGE_DURATION_MS = 250;
    /** Duration of the alpha animation used when a brick is hidden in keeps-space mode. */
    private static final int BRICK_ALPHA_DURATION_MS = 300;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    /** Cadence for advancing the media progress bar while a track is actively playing. 250ms
     *  is fast enough to look smooth on a thin bar and slow enough to not show up in profilers. */
    private static final long MEDIA_PROGRESS_TICK_MS = 250L;
    /** Gap between the play/pause indicator and the text it precedes, as a fraction of that
     *  text's size — same rationale as the icon's own size: it must track the font sliders. */
    private static final float STATE_ICON_GAP_RATIO = 0.25f;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 1000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
    private static final String GNSSSHARE_CLIENT_PACKAGE = "dezz.gnssshare.client";

    private static WidgetService instance;

    private Preferences prefs;

    /**
     * The render-side bricks that have their own object, by type. Created once with the service
     * and kept across overlay rebuilds — their status enums and cached device state must survive
     * a configuration change, and nothing re-registers their data sources afterwards. Only the
     * views are re-captured, in {@code bind}.
     */
    private final EnumMap<BrickType, RenderBrick> renderBricks = new EnumMap<>(BrickType.class);

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private GradientDrawable background = null;
    private int bgColor = -1;
    private int bgCornerRadius = -1;

    private int touchSlop;


    private UsageStatsManager usageStatsManager = null;
    private Set<String> hiddenInPackages;
    private String lastForegroundPackage;
    private boolean overlayHiddenByApp = false;

    /**
     * Number of in-flight transitions that have widened the WindowManager window to the
     * screen-width "buffer" so animations can play in a stable rectangle. Incremented when
     * a transition starts the buffer, decremented when it ends; the window is restored to
     * WRAP_CONTENT only when the counter reaches zero. Shared between:
     * <ul>
     *   <li>{@link #beginVisibilityTransition} (brick show/hide)</li>
     *   <li>The always-on {@link android.animation.LayoutTransition#CHANGING} on
     *       overlayContainer (any child changing measured size)</li>
     *   <li>The eager pre-empt in the {@code onLayoutChange} listener that catches a
     *       shrink one frame before {@code LayoutTransition.startTransition} would,
     *       so the window doesn't snap below the children that are still animating
     *       at their old positions</li>
     * </ul>
     */
    private int pendingBufferedTransitions = 0;

    /**
     * Closes the buffer opened eagerly by {@code onLayoutChange} when the content shrinks.
     * Posted with a delay slightly longer than {@link #BRICK_TRANSITION_DURATION_MS}; the
     * happy-path {@code LayoutTransition.endTransition} usually fires first and the
     * counter goes to zero on its own — this is the safety net for the case where no
     * {@code LayoutTransition} actually runs (e.g. a same-size measure that still
     * propagated through), so the window doesn't stay screen-wide forever.
     */
    private final Runnable shrinkBufferSafetyClose = this::endBufferedTransition;

    /**
     * Always-on {@link android.animation.LayoutTransition#CHANGING} animation installed on the
     * overlay container. Held as a field so {@link #beginVisibilityTransition} can disable
     * CHANGING for the duration of a visibility flip — otherwise the explicit ChangeBounds
     * inside the visibility {@link android.transition.TransitionSet} and the implicit CHANGING
     * triggered by sibling bricks shifting both play at once, producing the visible "double
     * animation". Re-enabled when the visibility transition's close runnable fires.
     */
    @Nullable
    private android.animation.LayoutTransition contentLayoutTransition;

    private Context themedContext;
    private int appliedThemePref = -1;

    /** Fires when the overlay's position or size changes so the settings UI can stay in sync. */
    public interface OverlayStateListener {
        void onOverlayStateChanged(int x, int y, int width, int height);
    }

    @Nullable private OverlayStateListener overlayStateListener;

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

    private final Runnable updateDateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(this, delay);
        }
    };

    private final Runnable foregroundAppCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            mainHandler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        prefs = new Preferences(this);
        renderBricks.put(BrickType.TIME, new TimeRenderBrick(this));
        renderBricks.put(BrickType.DATE, new DateRenderBrick(this));
        renderBricks.put(BrickType.WIFI, new WifiRenderBrick(this));
        renderBricks.put(BrickType.GPS, new GpsRenderBrick(this));
        renderBricks.put(BrickType.BLUETOOTH, new BluetoothRenderBrick(this));
        renderBricks.put(BrickType.INDOOR_TEMP, new TempRenderBrick(this, BrickType.INDOOR_TEMP,
                prefs.indoorTemp, R.id.indoorTempText));
        renderBricks.put(BrickType.OUTDOOR_TEMP, new TempRenderBrick(this, BrickType.OUTDOOR_TEMP,
                prefs.outdoorTemp, R.id.outdoorTempText));

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (!Permissions.allPermissionsGranted(this)) {
            prefs.widgetEnabled.set(false);
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            startMainActivity();
            stopSelf();
            return;
        }

        instance = this;

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        windowManager = getSystemService(WindowManager.class);

        // Re-evaluate brick visibility when the car SDK's asynchronous service connect finally
        // answers whether the car sensors exist — critical on the boot-autostart path, where
        // the first applyPreferences runs before the vendor service is up and would otherwise
        // hide configured car bricks until the user happens to open the settings UI.
        CarIntegrations.get(this).setAvailabilityChangedListener(() -> {
            if (binding != null) applyPreferences();
        });

        createOverlayView();
    }

    private void createOverlayView() {
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        for (RenderBrick brick : renderBricks.values()) {
            brick.bind(binding);
        }
        // Fresh views, fresh state: the progress bar starts out gone in the layout.
        progressBarShown = false;
        // Start invisible — the addView() below makes the window appear instantly; we then
        // fade the content in to match the symmetric fade-out the overlay does elsewhere.
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setVisibility(View.VISIBLE);
        // Listen on the INNER container, not the outer FrameLayout. During a visibility
        // transition we pre-expand the *window* (root) to screenWidth as a buffer for
        // TransitionManager; if we listened on the root we'd see that buffer expand as a
        // huge layout change and shove overlayX by hundreds of pixels (and persist it).
        // The inner container's bounds are what TransitionManager animates smoothly.
        binding.overlayContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            updateBackground();
            // Right-edge anchoring: when the widget content changes its measured width, shift the
            // window's left edge by the same amount so the right edge stays put. Done in a single
            // updateViewLayout to avoid the "shrink then slide" two-phase animation that
            // Gravity.RIGHT produces.
            if (params == null) return;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            boolean nonStatusBar = prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR;
            // No buffer guard here on purpose: the window's own width swings used to leak into
            // these bounds, but the container now measures against the display rather than the
            // window (see BufferingLinearLayout), so what arrives here is content width only.
            // Gating on pendingBufferedTransitions would be worse than useless — the size hint
            // raises that counter from onMeasure, i.e. before this listener ever runs.
            if (nonStatusBar
                    && prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
            notifyOverlayState();
        });

        // Synchronous "size about to change" hook. Fires from {@code onMeasure} of the
        // BufferingLinearLayout — earlier than OnLayoutChangeListener and earlier than
        // LayoutTransition.startTransition, both of which run after ViewRootImpl has
        // already pushed the new wrap_content dimensions to WindowManager. Catching it
        // mid-measure lets our updateViewLayout(screenWidth) win the race so the window
        // never snaps below the children that are about to animate. The safety runnable
        // is a fallback in case no LayoutTransition actually plays.
        // Seed the measure mode here as well as in applyPreferences: addView() happens a few
        // lines below and the first traversal must already know which regime it is in, without
        // depending on applyPreferences() being called before it.
        binding.overlayContainer.setMeasureUnconstrainedWidth(
                prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR);

        binding.overlayContainer.setSizeChangeHint((oldW, newW, oldH, newH) -> {
            if (params == null) return;
            if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) return;
            if (newW >= oldW) return;   // grow path already works
            if (pendingBufferedTransitions > 0) return;   // some transition already buffering
            beginBufferedTransition(true);
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            mainHandler.postDelayed(shrinkBufferSafetyClose,
                    BRICK_TRANSITION_DURATION_MS + 200);
        });

        // Universal "content size changed" animation: install a LayoutTransition with only the
        // CHANGING type enabled on the overlay container. Any child that changes its measured
        // size (clock minute rolls over, date string flips at midnight, media title scrolls to
        // a new track, status icon swaps drawable) will produce a smooth ChangeBounds-style
        // animation for itself and any siblings it pushes around. CHANGE_APPEARING / APPEARING
        // / DISAPPEARING are left disabled — those cases are handled by our explicit
        // {@link #beginVisibilityTransition} that knows about the window-buffer trick.
        // We hook startTransition / endTransition into the same buffered-transition counter so
        // the window doesn't snap mid-animation when CHANGING runs solo, and so concurrent
        // CHANGING + visibility transitions coexist correctly.
        contentLayoutTransition = new android.animation.LayoutTransition();
        android.animation.LayoutTransition lt = contentLayoutTransition;
        lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(android.animation.LayoutTransition.CHANGING, CONTENT_CHANGE_DURATION_MS);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGING,
                new android.view.animation.AccelerateDecelerateInterpolator());
        lt.addTransitionListener(new android.animation.LayoutTransition.TransitionListener() {
            @Override
            public void startTransition(android.animation.LayoutTransition transition,
                                        android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                beginBufferedTransition(true);
            }

            @Override
            public void endTransition(android.animation.LayoutTransition transition,
                                      android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                endBufferedTransition();
            }
        });
        binding.overlayContainer.setLayoutTransition(lt);

        // Set up drag listener (just registers a touch listener on the root view — safe to do
        // before addView since the listener captures touches once attached).
        setupDragListener();

        // Initialize params and addView BEFORE applyPreferences. The first applyPreferences()
        // call inside this method walks through applyBrickVisibility / beginVisibilityTransition
        // which expects to expand the window via WindowManager.updateViewLayout — that requires
        // params and the view to be attached. Doing applyPreferences before addView used to
        // leave pendingBufferedTransitions stuck at 1 forever, which suppressed every later
        // shrink-side buffer pre-empt and made content-shrink animations clip their right edge.
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        params = new WindowManager.LayoutParams(
                statusBar
                        ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                ,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = statusBar ? 0 : prefs.overlayX.get();
        params.y = statusBar ? 0 : prefs.overlayY.get();
        params.windowAnimations = 0;

        try {
            windowManager.addView(binding.getRoot(), params);
        } catch (Exception e) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        applyPreferences();

        // Fade in the freshly-added view; addView itself is instant.
        binding.getRoot().animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .start();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-create date/time formatters so a locale change is reflected.
        for (RenderBrick brick : renderBricks.values()) {
            brick.onConfigurationChanged();
        }
        // If the user is in "follow system" mode, the system uiMode flip means the cached
        // themedContext now points at the wrong configuration — invalidate so the next
        // applyPreferences() rebuilds it.
        themedContext = null;
        appliedThemePref = -1;

        if (binding != null) {
            windowManager.removeView(binding.getRoot());
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        hiddenInPackages = prefs.hideInPackages.get();
        rebuildEffectiveHideLists();
        updateForegroundAppTracking();
        updateThemedContext();

        updateBackground();
        updateDateTime();

        List<BrickType> bricks = BrickType.parseOrder(prefs.brickOrder.get());
        Set<BrickType> bricksSet = EnumSet.noneOf(BrickType.class);
        bricksSet.addAll(bricks);

        // The content-change LayoutTransition only makes sense in floating mode, where the
        // widget's own width animates as brick content grows/shrinks. In status-bar mode the
        // row is full-width with fixed groups — there is nothing to animate, but the CHANGING
        // tracker still arms itself on every layout pass of the container and on OEM head
        // units it visibly "regroups" the media row once a second (triggered by the periodic
        // GNSS/status redraws) while the marquee scrolls. Disable it entirely there.
        binding.overlayContainer.setLayoutTransition(
                prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR ? null : contentLayoutTransition);

        // Floating mode must measure at natural width (see BufferingLinearLayout); the status-bar
        // row must not — it spreads its start/center/end groups across the width it is given.
        binding.overlayContainer.setMeasureUnconstrainedWidth(
                prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR);

        // Reorder children of the root LinearLayout to match brickOrder. Hidden bricks are
        // appended at the end with View.GONE — kept attached so we don't need to re-bind state.
        reorderBricks(bricks);

        // Apply each brick's settings (size/font, outline, margins) — independent of visibility.
        applyMediaBrickSettings();
        for (RenderBrick brick : renderBricks.values()) {
            brick.applySettings();
        }

        applyBrickVisibility(bricksSet);
        applyOverlayPosition();

        // Re-apply icon style for the current state — icon style and outline may have changed.
        for (RenderBrick brick : renderBricks.values()) {
            brick.refreshContent();
        }

        // User-controllable global padding around the widget content (four independent sides).
        // Was previously auto-computed as half of the largest brick dimension — many users found
        // it too wide on small head units, so it's now explicit prefs. Slight outline clipping
        // at thin paddings is acceptable.
        // Padding goes on the INNER container — that's the view with the rounded background.
        // Putting it on the outer FrameLayout instead leaves a transparent gutter around the
        // background rect (visible at non-zero padding) and shifts the background's rounded
        // corners outside the touchable area.
        binding.overlayContainer.setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());

        // Lock the widget height to the tallest brick that's in the user's chosen order —
        // including bricks currently hidden per-app. Otherwise hiding e.g. a big Time brick
        // would let the row shrink vertically and the remaining icons would re-center up,
        // breaking alignment with the device status bar that users carefully tune.
        // {@code setMinimumHeight} compares against the view's *total* measured height (content
        // plus padding), so we add the vertical padding here — otherwise when the tallest brick
        // is visible the view measures to {@code maxBrick + padding} and when it's hidden it
        // collapses to {@code minHeight = maxBrick} (without padding), shrinking by the padding
        // amount on every hide.
        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(bricksSet) + verticalPadding);

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (bricksSet.contains(BrickType.TIME) || bricksSet.contains(BrickType.DATE)) {
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(updateDateTimeRunnable, delay);
        }

        // Each brick reconciles its own data source with whether the user still has it in the
        // row. The three shapes genuinely differ — Wi-Fi and GNSS acquire lazily behind a manager
        // field, Bluetooth re-registers every pass — so idempotence belongs to the brick, not to
        // a contract imposed here.
        for (RenderBrick brick : renderBricks.values()) {
            brick.syncSource(bricksSet.contains(brick.type));
        }

        if (bricksSet.contains(BrickType.MEDIA) && Permissions.isNotificationAccessGranted(this)) {
            enableMediaTracking();
        } else {
            disableMediaTracking();
            binding.mediaContainer.setVisibility(View.GONE);
        }

    }

    /** Last rendered media subtitle — used to distinguish a real track change from the
     *  once-a-second metadata republishes some players emit (see updateMediaInfo). */
    @Nullable
    private String lastMediaSubtitle = null;

    /** Intended progress-bar visibility. Ours, not the view's: {@code View.getVisibility()} is not
     *  authoritative while a transition owns the view — see {@link #setProgressBarShown}. Reset
     *  whenever the overlay is re-inflated, since the XML default is {@code gone}. */
    private boolean progressBarShown = false;

    /** {@code TextView.setText} drops the layout and forces a relayout even for identical text —
     *  callers on hot paths (per-second player callbacks) must skip unchanged values. */
    private static void setTextIfChanged(android.widget.TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private void reorderBricks(List<BrickType> bricks) {
        // Adding/removing a brick changes child order/membership of the root.
        // applyBrickVisibility() (called right after this from applyPreferences) drives the
        // per-brick fade + width animation that gives us the "dynamic island" feel; we
        // just rearrange children here.
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            reorderForStatusBar(bricks);
        } else {
            reorderForFloating(bricks);
        }
    }

    private void reorderForFloating(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        // Status-bar group containers and spacers are hidden in floating mode and emptied so
        // bricks live as direct children of the root again.
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();
        binding.startGroup.setVisibility(View.GONE);
        binding.centerGroup.setVisibility(View.GONE);
        binding.endGroup.setVisibility(View.GONE);
        binding.startCenterSpacer.setVisibility(View.GONE);
        binding.centerEndSpacer.setVisibility(View.GONE);

        List<View> expected = new ArrayList<>();
        // Re-include the (empty) groups + spacers so their visibility=GONE keeps them out of
        // measure but the views remain attached to the same root for next switch.
        expected.add(binding.startGroup);
        expected.add(binding.startCenterSpacer);
        expected.add(binding.centerGroup);
        expected.add(binding.centerEndSpacer);
        expected.add(binding.endGroup);
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v != null) expected.add(v);
        }
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) expected.add(v);
            }
        }
        applyChildOrder(root, expected);
    }

    private void reorderForStatusBar(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        // Detach bricks from wherever they currently sit (root or any group).
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();

        // Root order: startGroup, spacer, centerGroup, spacer, endGroup. Hidden bricks dangle off
        // the root after these so they remain attached but invisible.
        List<View> rootChildren = new ArrayList<>();
        rootChildren.add(binding.startGroup);
        rootChildren.add(binding.startCenterSpacer);
        rootChildren.add(binding.centerGroup);
        rootChildren.add(binding.centerEndSpacer);
        rootChildren.add(binding.endGroup);
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) rootChildren.add(v);
            }
        }
        applyChildOrder(root, rootChildren);

        // Distribute visible bricks into the proper alignment group.
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v == null) continue;
            int alignment = clampAlignment(prefs.statusAlignmentFor(type).get());
            LinearLayout target = (alignment == 1) ? binding.centerGroup
                    : (alignment == 2) ? binding.endGroup
                    : binding.startGroup;
            target.addView(v);
        }

        binding.startGroup.setVisibility(View.VISIBLE);
        binding.centerGroup.setVisibility(View.VISIBLE);
        binding.endGroup.setVisibility(View.VISIBLE);
        binding.startCenterSpacer.setVisibility(View.VISIBLE);
        binding.centerEndSpacer.setVisibility(View.VISIBLE);
    }

    private static void applyChildOrder(ViewGroup parent, List<View> expected) {
        boolean inOrder = parent.getChildCount() == expected.size();
        if (inOrder) {
            for (int i = 0; i < expected.size(); i++) {
                if (parent.getChildAt(i) != expected.get(i)) {
                    inOrder = false;
                    break;
                }
            }
        }
        if (inOrder) return;
        parent.removeAllViews();
        for (View v : expected) {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
            parent.addView(v);
        }
    }

    private static int clampAlignment(int v) {
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }

    /** A brick's root view. Everything but the media brick answers for itself. */
    @Nullable
    private View viewForBrick(BrickType type) {
        RenderBrick brick = renderBricks.get(type);
        if (brick != null) return brick.view();
        return type == BrickType.MEDIA ? binding.mediaContainer : null;
    }

    private void applyMediaBrickSettings() {
        int textColor = ContextCompat.getColor(themedContext, R.color.text_primary);

        // Source line: independent font, opacity, outline.
        Typeface sourceTypeface = Fonts.resolve(this, prefs.media.sourceFontFamily.get(),
                prefs.media.sourceFontBold.get(), prefs.media.sourceFontItalic.get());
        binding.mediaAppText.setOutlineColor(textOutlineColor(prefs.media.sourceOutlineAlpha.get()));
        binding.mediaAppText.setOutlineWidth(prefs.media.sourceOutlineWidth.get());
        binding.mediaAppText.setTextColor(textColor);
        binding.mediaAppText.setTypeface(sourceTypeface);
        binding.mediaAppText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.sourceFontSize.get());
        binding.mediaAppText.setAlpha(prefs.media.sourceContentAlpha.get() / 255f);

        // Title line: existing media.* font + opacity + outline (TextBrickPrefs inherited).
        Typeface titleTypeface = Fonts.resolve(this, prefs.media.fontFamily.get(),
                prefs.media.fontBold.get(), prefs.media.fontItalic.get());
        binding.mediaTitleText.setOutlineColor(textOutlineColor(prefs.media.outlineAlpha.get()));
        binding.mediaTitleText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaTitleText.setTextColor(textColor);
        binding.mediaTitleText.setTypeface(titleTypeface);
        binding.mediaTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        binding.mediaTitleText.setAlpha(prefs.media.contentAlpha.get() / 255f);

        // Source line is always static + ellipsized; only the title scrolls. Source is short
        // and a constant moving marquee on it would be more distracting than helpful.
        binding.mediaAppText.setMarqueeEnabled(false);
        binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get());

        applyMediaStateIcon(textColor);

        // Duration text — independent font size / alpha / outline so the user can dial it down
        // (typically the duration is rendered smaller and dimmer than the track subtitle).
        binding.mediaDurationText.setTypeface(titleTypeface);
        binding.mediaDurationText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.durationFontSize.get());
        binding.mediaDurationText.setTextColor(textColor);
        binding.mediaDurationText.setOutlineColor(textOutlineColor(prefs.media.durationOutlineAlpha.get()));
        binding.mediaDurationText.setOutlineWidth(prefs.media.durationOutlineWidth.get());
        binding.mediaDurationText.setAlpha(prefs.media.durationContentAlpha.get() / 255f);

        RenderBrick.applyHorizontalMargins(binding.mediaContainer, prefs.media.marginStart.get(), prefs.media.marginEnd.get());
        binding.mediaContainer.setTranslationY(prefs.media.adjustY.get());
        // Container alpha back to full — per-line alpha is set above so the two values don't
        // multiply through the parent.
        binding.mediaContainer.setAlpha(1f);
        applyMediaMaxWidth(binding.mediaAppText);
        applyMediaMaxWidth(binding.mediaTitleText);
        // Alignment applies to the two ROWS — they, not the text views, are the children of the
        // vertical container, and layout_gravity on a child of a horizontal LinearLayout only
        // ever moves it vertically.
        applyMediaChildAlignment(binding.mediaSourceRow, prefs.media.sourceAlignment.get());
        applyMediaChildAlignment(binding.mediaTitleRow, prefs.media.alignment.get());
        // Vertical gap between the two lines, applied as the title row's top margin.
        LinearLayout.LayoutParams titleLp =
                (LinearLayout.LayoutParams) binding.mediaTitleRow.getLayoutParams();
        titleLp.topMargin = prefs.media.lineGap.get();
        binding.mediaTitleRow.setLayoutParams(titleLp);
    }

    /**
     * Playback-state indicator. It lives at the head of the source row — "▶ Spotify" reads as one
     * statement — but the source line is optional, so when it's off the icon is re-parented to the
     * head of the title row instead of vanishing with its host. Either way it takes the size,
     * outline and opacity of the line it sits on, so it scales with that line's font-size slider
     * and flips colour with the widget theme like the text around it.
     */
    private void applyMediaStateIcon(int textColor) {
        boolean onSourceRow = prefs.media.showSource.get();
        LinearLayout host = onSourceRow ? binding.mediaSourceRow : binding.mediaTitleRow;
        ViewGroup parent = (ViewGroup) binding.mediaStateIcon.getParent();
        if (parent != host) {
            if (parent != null) parent.removeView(binding.mediaStateIcon);
            host.addView(binding.mediaStateIcon, 0);
        }

        int fontSize = onSourceRow ? prefs.media.sourceFontSize.get() : prefs.media.fontSize.get();
        int outlineAlpha = onSourceRow
                ? prefs.media.sourceOutlineAlpha.get() : prefs.media.outlineAlpha.get();
        int outlineWidth = onSourceRow
                ? prefs.media.sourceOutlineWidth.get() : prefs.media.outlineWidth.get();
        int contentAlpha = onSourceRow
                ? prefs.media.sourceContentAlpha.get() : prefs.media.contentAlpha.get();
        binding.mediaStateIcon.setTextSizePx(fontSize);
        binding.mediaStateIcon.setIconColor(textColor);
        binding.mediaStateIcon.setOutlineColor(textOutlineColor(outlineAlpha));
        binding.mediaStateIcon.setOutlineWidth(outlineWidth);
        binding.mediaStateIcon.setAlpha(contentAlpha / 255f);

        // Gap to the text scales with that text too — a fixed one would glue the icon to a 60px
        // source line and strand it next to a 12px one.
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) binding.mediaStateIcon.getLayoutParams();
        int gap = Math.round(fontSize * STATE_ICON_GAP_RATIO);
        if (lp.getMarginEnd() != gap) {
            lp.setMarginEnd(gap);
            binding.mediaStateIcon.setLayoutParams(lp);
        }

        // Switching the indicator off has to be honored here too, not only in updateMediaInfo:
        // this is the only media code that runs when there is no active session, so a stale
        // VISIBLE icon would otherwise be impossible to turn off until something played again.
        // Only the off-direction is applied — turning it back on stays with updateMediaInfo,
        // which additionally requires the line hosting the icon to actually carry text.
        if (!prefs.media.showPlaybackState.get()) {
            binding.mediaStateIcon.setVisibility(View.GONE);
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
        view.setMaxWidth(prefs.media.maxWidth.get());
    }

    private int textOutlineColor(int alpha) {
        return (ContextCompat.getColor(themedContext, R.color.text_outline) & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Rebuilds {@link #themedContext} so theme-dependent colour lookups respect the user's
     * "Widget theme" preference. Pref values: 0 = follow system, 1 = always light, 2 = always
     * dark, 3 = inverse of system. Cached so we don't allocate a new Context on every
     * {@code applyPreferences()}; {@code onConfigurationChanged} invalidates the cache so the
     * inverse mode picks up system theme changes too.
     */
    private void updateThemedContext() {
        int pref = prefs.widgetTheme.get();
        if (themedContext != null && pref == appliedThemePref) return;
        if (pref == 0) {
            themedContext = this;
        } else {
            int uiMode;
            if (pref == 1) {
                uiMode = Configuration.UI_MODE_NIGHT_NO;
            } else if (pref == 2) {
                uiMode = Configuration.UI_MODE_NIGHT_YES;
            } else {
                int systemNight = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                uiMode = (systemNight == Configuration.UI_MODE_NIGHT_YES)
                        ? Configuration.UI_MODE_NIGHT_NO
                        : Configuration.UI_MODE_NIGHT_YES;
            }
            Configuration cfg = new Configuration(getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | uiMode;
            themedContext = createConfigurationContext(cfg);
        }
        appliedThemePref = pref;
    }

    private final EnumMap<BrickType, Set<String>> effectiveHideLists = new EnumMap<>(BrickType.class);

    private void rebuildEffectiveHideLists() {
        effectiveHideLists.clear();
        for (BrickType type : BrickType.values()) {
            BrickType source = prefs.effectiveHideSourceFor(type);
            effectiveHideLists.put(type, prefs.hideListFor(source).get());
        }
    }

    private boolean isBrickHiddenByApp(BrickType type) {
        if (lastForegroundPackage == null) return false;
        Set<String> list = effectiveHideLists.get(type);
        return list != null && list.contains(lastForegroundPackage);
    }

    private boolean anyBrickHasHideList() {
        for (Set<String> s : effectiveHideLists.values()) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    private void applyBrickVisibility(Set<BrickType> bricksSet) {
        if (binding == null) return;
        BrickTarget[] targets = {
                resolveTarget(renderBricks.get(BrickType.TIME), bricksSet),
                resolveTarget(renderBricks.get(BrickType.DATE), bricksSet),
                resolveTarget(renderBricks.get(BrickType.WIFI), bricksSet),
                resolveTarget(renderBricks.get(BrickType.GPS), bricksSet),
                resolveTarget(renderBricks.get(BrickType.BLUETOOTH), bricksSet),
                resolveTarget(renderBricks.get(BrickType.INDOOR_TEMP), bricksSet),
                resolveTarget(renderBricks.get(BrickType.OUTDOOR_TEMP), bricksSet),
        };

        // Media has the extra session gate, so we build its BrickTarget here. The gate is the
        // session, not just the brick list: with no controller there is nothing to render, and
        // driving the container VISIBLE anyway paints an empty brick — no source, no title, and
        // the state icon still at its inflate-default VISIBLE (drawing a play triangle, since
        // setPaused has never run). That is the phantom indicator seen on head units whose player
        // leaves no session behind: updateMediaInfo sets the container GONE, then any later
        // settings pass or foreground-app change re-showed it here.
        boolean mediaShouldBeGone = !bricksSet.contains(BrickType.MEDIA)
                || pickActiveMediaController() == null;
        boolean mediaHiddenByApp = !mediaShouldBeGone && isBrickHiddenByApp(BrickType.MEDIA);
        BrickTarget mediaTarget;
        if (mediaShouldBeGone) {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
        } else if (mediaHiddenByApp) {
            if (prefs.hideKeepsSpaceFor(BrickType.MEDIA).get()) {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE, 0f);
            } else {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
            }
        } else {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE,
                    prefs.media.contentAlpha.get() / 255f);
        }

        // Categorise the changes. Visibility flips (VISIBLE↔GONE) get the TransitionManager +
        // window-buffer treatment; pure alpha changes (keep-space mode where the brick stays
        // in the layout) just get a plain alpha animation.
        java.util.List<BrickTarget> visibilityFlips = new java.util.ArrayList<>();
        java.util.List<BrickTarget> alphaOnly = new java.util.ArrayList<>();
        boolean expanding = false;
        for (BrickTarget t : targets) {
            if (t.view.getVisibility() != t.visibility) {
                visibilityFlips.add(t);
                if (t.visibility == View.VISIBLE) expanding = true;
            } else if (t.visibility == View.VISIBLE) {
                alphaOnly.add(t);
            }
        }
        // Media too.
        if (mediaTarget.view.getVisibility() != mediaTarget.visibility) {
            visibilityFlips.add(mediaTarget);
            if (mediaTarget.visibility == View.VISIBLE) expanding = true;
        }
        // Deliberately NOT an "else": the brick's children keep whatever hideMediaBrick() left
        // behind (row, icon and bar all GONE), and applyBrickTarget only touches the container —
        // so a container flipping back to VISIBLE would come up empty until the next player
        // callback. Refresh in both cases. This has to stay AFTER the flip check above, because
        // updateMediaInfo ends by setting the container VISIBLE itself, which would hide the flip
        // from that comparison and cost us the transition.
        if (mediaTarget.visibility == View.VISIBLE && !mediaShouldBeGone && !mediaHiddenByApp) {
            updateMediaInfo();
        }

        if (!visibilityFlips.isEmpty()) {
            // Scene root for TransitionManager is the INNER container — the outer FrameLayout
            // gets resized to a screen-width buffer via WindowManager, and we want the
            // transition to play inside the stable inner LinearLayout, not chase the buffer.
            beginVisibilityTransition(binding.overlayContainer, expanding);
        }

        // Apply all targets. For visibility flips Fade transition handles the alpha animation;
        // for alpha-only ones we run an explicit ViewPropertyAnimator.
        for (BrickTarget t : targets) {
            applyBrickTarget(t, visibilityFlips.contains(t));
        }
        applyBrickTarget(mediaTarget, visibilityFlips.contains(mediaTarget));

        // Per-brick alpha not covered by the Fade transition (keep-space VISIBLE→VISIBLE).
        // The bricks in alphaOnly might still want a visible-alpha update if contentAlpha
        // pref changed — handled by applyXxxBrickSettings setAlpha which runs before this.
    }

    /** Snapshot of the desired end state for a brick view. */
    private static final class BrickTarget {
        final View view;
        final int visibility;
        /** Target alpha when {@link #visibility} is {@code VISIBLE}; ignored otherwise. */
        final float visibleAlpha;
        BrickTarget(View view, int visibility, float visibleAlpha) {
            this.view = view;
            this.visibility = visibility;
            this.visibleAlpha = visibleAlpha;
        }
    }

    /**
     * Decide the final view state for a brick. {@code activeInLayout=false} (brick not in
     * the layout / Date with both flags off) → {@code GONE}, hard collapse. Otherwise honour
     * {@link Preferences#hideKeepsSpaceFor}: if true, render an INVISIBLE-equivalent (VISIBLE
     * view, alpha animated to 0); if false, plain GONE.
     */
    /** Overload for a brick that answers its own activity, opacity and view. */
    private BrickTarget resolveTarget(RenderBrick brick, Set<BrickType> order) {
        return resolveTarget(brick.type, brick.activeInLayout(order), brick.view(),
                Math.round(brick.contentAlpha() * 255f));
    }

    private BrickTarget resolveTarget(BrickType type, boolean activeInLayout, View view,
                                      int contentAlphaPref) {
        float baseAlpha = contentAlphaPref / 255f;
        if (!activeInLayout) {
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        if (isBrickHiddenByApp(type)) {
            if (prefs.hideKeepsSpaceFor(type).get()) {
                // VISIBLE-with-alpha-0 replaces the old INVISIBLE constant — same effect on
                // layout (space preserved) but animatable.
                return new BrickTarget(view, View.VISIBLE, 0f);
            }
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        return new BrickTarget(view, View.VISIBLE, baseAlpha);
    }

    /**
     * Applies a brick's target state. For visibility flips the heavy lifting is done by the
     * {@code TransitionManager} scene set up by {@link #beginVisibilityTransition} — we
     * just toggle {@code setVisibility} and the Fade transition cross-fades alpha while
     * ChangeBounds slides siblings into place. For alpha-only changes (keep-space hide)
     * we animate alpha explicitly.
     */
    private void applyBrickTarget(BrickTarget target, boolean handledByTransition) {
        if (target.visibility == View.GONE) {
            target.view.animate().cancel();
            target.view.setVisibility(View.GONE);
            return;
        }
        target.view.setVisibility(View.VISIBLE);
        if (handledByTransition) {
            // Fade transition animates the alpha for us; make sure the final value is the
            // brick's contentAlpha pref (not 1.0 from Fade's default).
            target.view.setAlpha(target.visibleAlpha);
        } else {
            target.view.animate().cancel();
            target.view.animate()
                    .alpha(target.visibleAlpha)
                    .setDuration(BRICK_ALPHA_DURATION_MS)
                    .start();
        }
    }

    /**
     * Runs the "buffer window" animation. Trick: before triggering the
     * scene change we either expand the window to screen width (when something is about to
     * appear) or pin it to its current width (when something is about to disappear). With
     * the window's outer rectangle frozen the children's Fade + ChangeBounds animations
     * play cleanly inside it; the listener restores the window to WRAP_CONTENT after the
     * transition so it snaps to the new natural size in one go. This sidesteps the
     * per-frame {@code updateViewLayout} approach that was visually broken on real hardware.
     */
    private void beginVisibilityTransition(ViewGroup sceneRoot, boolean expanding) {
        if (binding == null) return;
        beginBufferedTransition(expanding);

        // Suppress the always-on CHANGING animation for the duration of this visibility flip.
        // Sibling bricks shift positions when a brick appears/disappears, which LayoutTransition
        // would otherwise interpret as a content change and animate in parallel with our own
        // explicit ChangeBounds inside the TransitionSet — visible as a doubled motion.
        if (contentLayoutTransition != null) {
            contentLayoutTransition.disableTransitionType(
                    android.animation.LayoutTransition.CHANGING);
        }

        android.transition.TransitionSet tx = new android.transition.TransitionSet();
        android.transition.ChangeBounds changeBounds = new android.transition.ChangeBounds();
        android.transition.Fade fade = new android.transition.Fade();
        tx.addTransition(changeBounds);
        tx.addTransition(fade);
        tx.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        tx.setDuration(BRICK_TRANSITION_DURATION_MS);
        tx.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        // This transition choreographs BRICKS — the direct children of the scene root. A brick's
        // internals are not part of that choreography, but the capture walk is recursive, so
        // without this every descendant is a legal Fade/ChangeBounds target. The media brick's
        // children flip visibility on the player's schedule (a metadata republish lands about once
        // a second); one landing between beginDelayedTransition and the end-value capture on the
        // next pre-draw makes Fade adopt the view, and Fade writes setTransitionAlpha eagerly when
        // it creates the animator. If that animator is then dropped (Transition.createAnimators
        // favors an already-running one on the same view), nothing restores the alpha and the child
        // reports VISIBLE while drawing nothing — which is how hiding the clock over the desktop
        // took the media progress bar down with it.
        // excludeChildren keeps mediaContainer itself a target, so the brick still fades and
        // re-bounds as a unit; only its internals are off limits. TransitionSet forwards
        // excludeTarget to the transitions it holds but NOT excludeChildren, hence all three calls.
        changeBounds.excludeChildren(binding.mediaContainer, true);
        fade.excludeChildren(binding.mediaContainer, true);
        tx.excludeChildren(binding.mediaContainer, true);
        // Listener can leak the buffer counter if TransitionManager decides nothing
        // animatable changed and never fires the lifecycle callbacks — known foot-gun.
        // Guard with a single-shot close flag and a safety runnable that runs unconditionally
        // after slightly longer than the transition's own duration. Whichever fires first
        // closes the buffer; the other becomes a no-op.
        final boolean[] closed = {false};
        Runnable closeOnce = () -> {
            if (closed[0]) return;
            closed[0] = true;
            if (contentLayoutTransition != null) {
                contentLayoutTransition.enableTransitionType(
                        android.animation.LayoutTransition.CHANGING);
            }
            endBufferedTransition();
        };
        tx.addListener(new android.transition.Transition.TransitionListener() {
            @Override public void onTransitionStart(android.transition.Transition t) {}
            @Override public void onTransitionEnd(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionCancel(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionPause(android.transition.Transition t) {}
            @Override public void onTransitionResume(android.transition.Transition t) {}
        });
        android.transition.TransitionManager.beginDelayedTransition(sceneRoot, tx);
        mainHandler.postDelayed(closeOnce, BRICK_TRANSITION_DURATION_MS + 500);
    }

    /**
     * Open a window-buffered transition: if no other buffered transition is in flight, pre-resize
     * the WindowManager window to either screen width ({@code expanding}) or its current width
     * (shrinking), so the animation that follows plays inside a stable rectangle instead of
     * fighting wrap-content. Idempotent under nesting: re-entrant callers just bump the counter.
     */
    private void beginBufferedTransition(boolean expanding) {
        if (binding == null) return;
        if (pendingBufferedTransitions++ == 0) {
            if (params != null && prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                int oldWidth = params.width;
                if (expanding) {
                    params.width = getResources().getDisplayMetrics().widthPixels;
                } else {
                    int currentWidth = binding.getRoot().getWidth();
                    if (currentWidth > 0) params.width = currentWidth;
                }
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                    params.width = oldWidth;
                }
            }
        }
    }

    /** Closes a transition opened by {@link #beginBufferedTransition}. When the last in-flight
     *  transition ends, restores the window to WRAP_CONTENT so it snaps to natural size. */
    private void endBufferedTransition() {
        if (pendingBufferedTransitions <= 0) return;
        if (--pendingBufferedTransitions == 0) {
            // The safety runnable exists only to close a buffer nobody else closed. Once the
            // buffer is genuinely shut, a pending one would decrement a counter that by then
            // belongs to the NEXT transition and snap the window narrow mid-animation.
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            restoreWindowToWrapContent();
        }
    }

    private void restoreWindowToWrapContent() {
        if (params == null || binding == null) return;
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {}
    }

    private Set<BrickType> currentBrickSet() {
        Set<BrickType> set = EnumSet.noneOf(BrickType.class);
        set.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        return set;
    }

    /**
     * Computes the tallest brick height (in pixels) over all bricks currently in
     * {@code brickOrder}, regardless of per-app visibility. Used as the widget's minimum height so
     * a brick disappearing on a particular app doesn't shrink the row.
     *
     * Text bricks use {@link Paint#getFontMetricsInt()} on a copy of the TextView's paint at the
     * given pixel size — the same metrics {@code StaticLayout} reserves for one line, and every
     * text view here sets {@code includeFontPadding=false}.
     *
     * <p>The model assumes each text brick occupies the number of lines it is configured for.
     * That holds because the floating container measures its children against the display (see
     * {@link BufferingLinearLayout}) and the status-bar row is as wide as the screen, so nothing
     * wraps to an unplanned extra line.
     *
     * <p>It cannot see fallback line spacing: on a line that falls back to another font for some
     * glyph, {@code StaticLayout} widens ascent/descent to cover that font too, which no paint of
     * ours reports. The floor is then a little short — the same direction the old estimate erred
     * in, and bounded by the fallback font's overshoot.
     */
    private int computeMinWidgetHeight(Set<BrickType> bricks) {
        int h = 0;
        if (bricks.contains(BrickType.MEDIA)) {
            h = Math.max(h, mediaBrickHeight());
        }
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.countsTowardFloor(bricks)) {
                h = Math.max(h, brick.minHeight());
            }
        }
        return h;
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
        int titleRow = TextRenderBrick.lineHeight(binding.mediaTitleText, prefs.media.fontSize.get());
        if (prefs.media.showDuration.get()) {
            titleRow = Math.max(titleRow, TextRenderBrick.lineHeight(binding.mediaDurationText,
                    prefs.media.durationFontSize.get()));
        }
        int height;
        if (prefs.media.showSource.get()) {
            int sourceRow = TextRenderBrick.lineHeight(binding.mediaAppText, prefs.media.sourceFontSize.get());
            if (prefs.media.showPlaybackState.get()) {
                // The indicator rides the source line and takes that line's metrics.
                sourceRow = Math.max(sourceRow, MediaStateIconView.heightFor(
                        prefs.media.sourceFontSize.get(), prefs.media.sourceOutlineWidth.get()));
            }
            height = sourceRow + titleRow;
        } else {
            // With the source line off the indicator is re-parented onto the title row.
            if (prefs.media.showPlaybackState.get()) {
                titleRow = Math.max(titleRow, MediaStateIconView.heightFor(
                        prefs.media.fontSize.get(), prefs.media.outlineWidth.get()));
            }
            height = titleRow;
        }
        // The title row's top margin is applied unconditionally (applyMediaBrickSettings) and the
        // row is never hidden, so LinearLayout counts it even with the source line off.
        height += prefs.media.lineGap.get();
        if (prefs.media.progressBarEnabled.get()) {
            height += progressBarExtent();
        }
        return height;
    }

    /** Progress bar height plus its top margin, read from the layout so the sp values stay there. */
    private int progressBarExtent() {
        ViewGroup.LayoutParams lp = binding.mediaProgressBar.getLayoutParams();
        if (lp == null || lp.height < 0) return 0;   // WRAP_CONTENT / MATCH_PARENT: nothing to add
        int extent = lp.height;
        if (lp instanceof LinearLayout.LayoutParams) {
            extent += ((LinearLayout.LayoutParams) lp).topMargin;
        }
        return extent;
    }

    public void setOverlayStateListener(@Nullable OverlayStateListener listener) {
        this.overlayStateListener = listener;
        if (listener != null) {
            notifyOverlayState();
        }
    }

    private void notifyOverlayState() {
        if (overlayStateListener == null || params == null || binding == null) return;
        overlayStateListener.onOverlayStateChanged(
                params.x, params.y,
                binding.getRoot().getWidth(),
                binding.getRoot().getHeight());
    }

    /**
     * Pushes the saved widget position and mode-specific window params into the WindowManager.
     * Called from {@link #applyPreferences()} so the position sliders / mode switcher in
     * settings affect the widget live. Skipped when the widget isn't drawn yet.
     */
    private void applyOverlayPosition() {
        if (params == null || binding == null || windowManager == null) return;
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        int newWidth = statusBar
                ? WindowManager.LayoutParams.MATCH_PARENT
                : WindowManager.LayoutParams.WRAP_CONTENT;
        // During a buffered transition the window is intentionally pinned wider than
        // wrap_content so children can animate without being clipped. Overwriting
        // params.width here would snap the window mid-animation and also strand the
        // TransitionManager listener (no scene change → no onTransitionEnd → counter
        // leak). The buffer closer will restore wrap_content when it ends.
        if (pendingBufferedTransitions > 0 && !statusBar) {
            newWidth = params.width;
        }
        int newX = statusBar ? 0 : prefs.overlayX.get();
        int newY = statusBar ? 0 : prefs.overlayY.get();
        if (params.x == newX && params.y == newY && params.width == newWidth) return;
        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {
        }
    }

    private void enableMediaTracking() {
        if (mediaSessionManager != null) return;
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) return;
        ComponentName component = new ComponentName(this, MediaNotificationListener.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, mainHandler);
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
                c.registerCallback(mediaControllerCallback, mainHandler);
            }
        }
        updateMediaInfo();
    }

    /**
     * Nothing to show — hide the brick and reset the children whose visibility is otherwise only
     * decided on the happy path below. Leaving them at their inflate defaults (row and icon both
     * VISIBLE, the icon drawing a play triangle because setPaused has never run) is what let a
     * phantom indicator paint whenever something else drove the container VISIBLE.
     */
    private void hideMediaBrick() {
        binding.mediaContainer.setVisibility(View.GONE);
        binding.mediaSourceRow.setVisibility(View.GONE);
        binding.mediaStateIcon.setVisibility(View.GONE);
        setProgressBarShown(false);
        stopMediaProgressTicker();
    }

    private void updateMediaInfo() {
        if (binding == null) return;
        if (!currentBrickSet().contains(BrickType.MEDIA) || isBrickHiddenByApp(BrickType.MEDIA)) {
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
        boolean titleFirst = prefs.media.titleFirst.get();
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
            subtitle = getString(R.string.media_unknown_track);
        }
        PlaybackState playbackState = playing.getPlaybackState();
        // Pause shape only for an actual PAUSED; transient states (buffering / seeking) keep the
        // play shape so the icon doesn't flicker every time the user scrubs.
        // Players republish PlaybackState continuously (Yandex Music every second), and
        // TextView.setText unconditionally drops its layout and requests a full re-layout even
        // for identical text. On OEM head units that per-second layout storm makes the whole
        // title row visibly jitter while the marquee scrolls — so every setter here must be
        // a no-op when the value didn't actually change (MediaStateIconView.setPaused is).
        binding.mediaStateIcon.setPaused(playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PAUSED);
        String sourceLabel = getAppLabel(playing.getPackageName());
        binding.mediaAppText.setMarqueeText(sourceLabel);
        binding.mediaTitleText.setMarqueeText(subtitle);
        // Show the source row only when the user enabled it AND there is a name to show. Some
        // head-unit system audio routes (built-in radio, a Bluetooth profile) own a media session
        // with no resolvable package/label, so the label comes back empty; a visible-but-empty row
        // would just add dead vertical space.
        boolean showSourceRow = prefs.media.showSource.get() && !isEmpty(sourceLabel);
        binding.mediaSourceRow.setVisibility(showSourceRow ? View.VISIBLE : View.GONE);
        // Play/pause indicator: an optional adornment (its own setting) that annotates whichever
        // line hosts it — the source line when showSource is on, the title line otherwise
        // (applyMediaStateIcon does the re-parenting). It must never float alone, so it is bound to
        // its host line having text: on the source line that means a non-empty app label (the
        // no-label head-unit sessions above would otherwise strand a lone triangle in the row), on
        // the title line the subtitle always has a fallback so it stays. This is why the icon's own
        // visibility is toggled rather than the row's — with the source line off, the row is gone
        // yet the indicator still needs to ride the title line.
        boolean iconHostHasText = prefs.media.showSource.get()
                ? !isEmpty(sourceLabel) : !isEmpty(subtitle);
        binding.mediaStateIcon.setVisibility(
                prefs.media.showPlaybackState.get() && iconHostHasText ? View.VISIBLE : View.GONE);

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

        if (!prefs.media.showDuration.get()) {
            binding.mediaDurationText.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            // Leading space gives the gap between title and duration without an extra layout
            // margin pref — scales naturally with the duration font size.
            setTextIfChanged(binding.mediaDurationText, " " + formatTrackDuration(durationMs));
            binding.mediaDurationText.setVisibility(View.VISIBLE);
        } else if (trackChanged) {
            // New track with no usable duration (live stream) — hide for real.
            binding.mediaDurationText.setVisibility(View.GONE);
        }
        // else: transient blip on the same track — keep the last shown value.

        // Progress bar visibility is decided here ONLY (updateMediaProgress never touches it —
        // see the comment there). Same blip-tolerant policy as the duration text.
        if (!prefs.media.progressBarEnabled.get()) {
            setProgressBarShown(false);
        } else if (durationMs > 0L) {
            setProgressBarShown(true);
        } else if (trackChanged) {
            setProgressBarShown(false);
        }

        binding.mediaContainer.setVisibility(View.VISIBLE);

        updateMediaProgress(playing);
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
        if (progressBarShown == shown && binding.mediaProgressBar.getVisibility()
                == (shown ? View.VISIBLE : View.GONE)) {
            return;
        }
        progressBarShown = shown;
        if (shown) {
            binding.mediaProgressBar.setColor(
                    ContextCompat.getColor(themedContext != null ? themedContext : this,
                            R.color.text_primary));
        }
        binding.mediaProgressBar.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /**
     * Snap the progress bar to the current playback position and arm/disarm the periodic ticker.
     * Called both from {@link #updateMediaInfo} (state/metadata flips) and from
     * {@link #mediaProgressTick} (every ~250ms while playing) to advance the bar smoothly.
     */
    private void updateMediaProgress(@Nullable MediaController playing) {
        if (binding == null) return;
        // Visibility policy: this method NEVER changes the bar's visibility. Flipping
        // GONE/VISIBLE changes the media container's height and relayouts the whole brick
        // row — and players like Yandex Music republish state/metadata every second, with
        // the duration transiently missing, which turned that flip into a once-a-second
        // visible "regroup" of the row while the marquee scrolls. Visibility is decided
        // solely in updateMediaInfo (real track/state changes); here we only advance the
        // fill fraction — a pure repaint.
        if (!prefs.media.progressBarEnabled.get() || playing == null || !progressBarShown) {
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

        binding.mediaProgressBar.setProgress((float) actualPosition / (float) duration);

        if (state.getState() == PlaybackState.STATE_PLAYING) {
            // Re-arm — the new postDelayed replaces any previously queued one, idempotent.
            mainHandler.removeCallbacks(mediaProgressTick);
            mainHandler.postDelayed(mediaProgressTick, MEDIA_PROGRESS_TICK_MS);
        } else {
            stopMediaProgressTicker();
        }
    }

    private void stopMediaProgressTicker() {
        mainHandler.removeCallbacks(mediaProgressTick);
    }

    private final Runnable mediaProgressTick = () -> updateMediaProgress(pickActiveMediaController());

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
     * Recognise the literal "Unknown artist" / "Неизвестный исполнитель" placeholders that
     * some players write into the artist field when the tag is missing — case-insensitive
     * and whitespace-tolerant.
     */
    private static boolean isUnknownArtistPlaceholder(@Nullable String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        return trimmed.equalsIgnoreCase("unknown artist")
                || trimmed.equalsIgnoreCase("неизвестный исполнитель");
    }

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
            int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
            last = (slash >= 0 && slash < raw.length() - 1) ? raw.substring(slash + 1) : raw;
        }
        if (isEmpty(last)) return null;
        int dot = last.lastIndexOf('.');
        if (dot > 0) {
            last = last.substring(0, dot);
        }
        return android.net.Uri.decode(last);
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

    private static boolean isMediaActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_CONNECTING:
                return true;
            default:
                return false;
        }
    }

    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    private void updateForegroundAppTracking() {
        boolean needTracking = !hiddenInPackages.isEmpty() || anyBrickHasHideList();
        boolean accessibilityActive = WidgetAccessibilityService.getInstance() != null;
        boolean usageGranted = Permissions.isUsageAccessGranted(this);
        // Two paths to the foreground package:
        //   - AccessibilityService (preferred): per-display data, multi-display safe.
        //   - UsageStatsManager (fallback): global, single foreground across all displays.
        // We only poll when neither path is being driven by events: the accessibility service
        // pushes via {@link #onForegroundDisplayMapUpdated()}, no polling needed.
        boolean shouldPoll = needTracking && !accessibilityActive && usageGranted;
        if (needTracking && (accessibilityActive || usageGranted)) {
            if (usageGranted && usageStatsManager == null) {
                usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            }
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            if (shouldPoll) {
                mainHandler.post(foregroundAppCheckRunnable);
            }
            // If accessibility just connected, recompute once now — we won't get an event
            // until something actually changes on a display.
            if (accessibilityActive) {
                checkForegroundApp();
            }
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            applyOverlayVisibility(false);
        }
    }

    /**
     * Called by {@link WidgetAccessibilityService} when the per-display foreground map changes.
     * Recomputes visibility based on the package on <i>our</i> display.
     */
    public void onForegroundDisplayMapUpdated() {
        mainHandler.post(this::checkForegroundApp);
    }

    /**
     * Called by {@link WidgetAccessibilityService} when its connection state flips — connect
     * or disconnect. Re-evaluates which foreground-tracking pipeline to use (accessibility
     * push vs. UsageStats poll).
     */
    public void onForegroundTrackingPathChanged() {
        mainHandler.post(this::updateForegroundAppTracking);
    }

    private void checkForegroundApp() {
        if (hiddenInPackages.isEmpty() && !anyBrickHasHideList()) return;

        WidgetAccessibilityService a11y = WidgetAccessibilityService.getInstance();
        String latestPackage;
        if (a11y != null) {
            // Display-aware: look up the foreground package on our overlay's display only.
            // If the accessibility framework hasn't reported anything for that display yet,
            // fall through to the UsageStats path so we're not blind on first start.
            int myDisplayId = currentOverlayDisplayId();
            latestPackage = a11y.getForegroundPackageOnDisplay(myDisplayId);
            if (latestPackage == null && usageStatsManager != null
                    && Permissions.isUsageAccessGranted(this)) {
                latestPackage = latestPackageFromUsageStats();
            }
        } else {
            // Global path — works on single-display devices.
            if (usageStatsManager == null) return;
            if (!Permissions.isUsageAccessGranted(this)) {
                updateForegroundAppTracking();
                return;
            }
            latestPackage = latestPackageFromUsageStats();
        }
        if (latestPackage == null) return;

        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
        if (changed && binding != null) {
            applyBrickVisibility(currentBrickSet());
        }
    }

    /** Display ID our overlay's window is attached to. Defaults to {@code DEFAULT_DISPLAY}
     *  if we can't determine it (single-display devices or pre-attach). */
    private int currentOverlayDisplayId() {
        if (binding == null) return android.view.Display.DEFAULT_DISPLAY;
        android.view.Display display = binding.getRoot().getDisplay();
        return display != null ? display.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
    }

    /** Extracts the most recent foreground package from {@link UsageStatsManager}. Null if
     *  nothing was reported in the lookback window. */
    @Nullable
    private String latestPackageFromUsageStats() {
        if (usageStatsManager == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - FOREGROUND_APP_LOOKBACK_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latest = lastForegroundPackage;
        long latestTimestamp = 0;
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latest = event.getPackageName();
                }
            }
        }
        return latest;
    }

    private void applyOverlayVisibility(boolean hide) {
        if (overlayHiddenByApp == hide) {
            return;
        }
        overlayHiddenByApp = hide;
        if (binding == null) return;
        View root = binding.getRoot();
        root.animate().cancel();
        if (hide) {
            // Animate to fully transparent, then collapse so the window stops occupying space.
            root.animate()
                    .alpha(0f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .withEndAction(() -> {
                        if (overlayHiddenByApp) root.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            // The animate().cancel() above leaves alpha at whatever it was mid-animation;
            // start the fade-in from the current value to its target of 1f.
            root.setVisibility(View.VISIBLE);
            root.animate()
                    .alpha(1f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .start();
        }
    }

    private void updateBackground() {
        if (binding == null) {
            return;
        }
        if (themedContext == null) {
            updateThemedContext();
        }
        // Read from the inner container, which is where the background drawable lives and what
        // TransitionManager animates. Reading from getRoot() would, during a visibility
        // transition, briefly return the screen-width window buffer and cap maxRadius too high.
        int width = binding.overlayContainer.getWidth();
        int height = binding.overlayContainer.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int maxRadius = Math.min(width, height) / 2;
        int backgroundCornerRadius = (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR)
                ? 0
                : maxRadius * prefs.backgroundCornerRadius.get() / 100;
        int backgroundColor = ContextCompat.getColor(themedContext, R.color.widget_background) & 0x00FFFFFF | (prefs.backgroundAlpha.get() << 24);
        binding.overlayContainer.setBackground(getBackground(backgroundColor, backgroundCornerRadius));
    }

    private Drawable getBackground(int color, int cornerRadius) {
        if (this.background == null || color != this.bgColor || cornerRadius != this.bgCornerRadius) {
            this.background = new GradientDrawable();
            this.background.setColor(color);
            this.background.setCornerRadius(cornerRadius);
            this.bgColor = color;
            this.bgCornerRadius = cornerRadius;
        }

        return this.background;
    }

    /** The shared minute tick. Only bricks the user actually has in the row are redrawn. */
    private void updateDateTime() {
        if (binding == null) return;
        Set<BrickType> order = currentBrickSet();
        java.util.Date now = new java.util.Date();
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.needsClockTick() && order.contains(brick.type)) {
                brick.onClockTick(now);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragListener() {
        binding.getRoot().setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) binding.getRoot().getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
                        // Pinned to (0, 0) full-width — drag is disabled, but consume the event so
                        // ACTION_UP still arrives for click handling.
                        return true;
                    }
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(binding.getRoot(), params);
                    notifyOverlayState();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                        savePosition();
                    }

                    // Handle click
                    if (Math.abs(event.getRawX() - initialTouchX) < touchSlop && Math.abs(event.getRawY() - initialTouchY) < touchSlop) {
                        if (binding.wifiStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.wifiStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }
                        if (binding.gnssStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.gnssStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(GNSSSHARE_CLIENT_PACKAGE);
                            if (intent == null) {
                                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }

                        startMainActivity();
                    }
                    return true;
            }
            return false;
        });
    }

    private void startMainActivity() {
        Intent startIntent = new Intent(WidgetService.this, MainActivity.class);
        startIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStartActivity(startIntent);
    }

    /**
     * Some car head units don't ship the system Wi-Fi / location / app-info activities at all,
     * so launching them from the overlay throws ActivityNotFoundException and tears down the
     * service process. Swallow the failure — the icon tap is non-essential.
     */
    private void safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed for " + intent.getAction(), t);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_title), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_status_gps_good).setContentIntent(pendingIntent).setOngoing(true).build();
    }

    private void savePosition() {
        if (params != null) {
            prefs.overlayX.set(params.x);
            prefs.overlayY.set(params.y);
        }
    }


    @NonNull
    @Override
    public Context context() {
        return this;
    }

    @NonNull
    @Override
    public Context themed() {
        // themedContext is momentarily null between onConfigurationChanged (which invalidates it)
        // and the next applyPreferences that rebuilds it. A status callback landing in that window
        // must not crash, so fall back to the service context.
        return themedContext != null ? themedContext : this;
    }

    @NonNull
    @Override
    public Preferences prefs() {
        return prefs;
    }

    @NonNull
    @Override
    public Handler handler() {
        return mainHandler;
    }

    @Override
    public void onDestroy() {
        instance = null;

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        mainHandler.removeCallbacks(foregroundAppCheckRunnable);
        mainHandler.removeCallbacks(mediaProgressTick);
        mainHandler.removeCallbacks(shrinkBufferSafetyClose);

        for (RenderBrick brick : renderBricks.values()) {
            brick.onDestroy();
        }

        if (binding != null && windowManager != null) {
            windowManager.removeView(binding.getRoot());
        }

        disableMediaTracking();
        // Drop car sensor subscriptions but keep the process-wide integration alive — the
        // settings UI may still query isBrickSupported after the overlay service stops.
        CarIntegration car = CarIntegrations.get(this);
        car.setAvailabilityChangedListener(null);
        car.unsubscribe(BrickType.INDOOR_TEMP);
        car.unsubscribe(BrickType.OUTDOOR_TEMP);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static WidgetService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private static Rect getBounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
