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
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
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
import java.util.Set;

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

    /**
     * The shared positional feed. Created before the bricks — they capture it in their
     * constructors — and destroyed here rather than by a brick, so a service stop cannot leave a
     * location listener or a receiver behind.
     */
    private GnssProvider gnssProvider;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Re-applies preferences when the car integration learns something new about sensor
     * availability — the vendor service finishing its asynchronous connect, or a sensor
     * delivering its first reading. Without this, car bricks configured by the user stay hidden
     * after a boot-autostart, because the first applyPreferences ran before the SDK was ready.
     */
    private final Runnable carAvailabilityListener = () -> {
        if (binding != null) applyPreferences();
    };
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
        gnssProvider = new GnssProvider(this, mainHandler);
        renderBricks.put(BrickType.TIME, new TimeRenderBrick(this));
        renderBricks.put(BrickType.DATE, new DateRenderBrick(this));
        renderBricks.put(BrickType.MEDIA, new MediaRenderBrick(this));
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
        CarIntegrations.get(this).addAvailabilityListener(carAvailabilityListener);

        createOverlayView();
    }

    private void createOverlayView() {
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        for (RenderBrick brick : renderBricks.values()) {
            brick.bind(binding);
        }
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
        if (anyBrickNeedsClockTick(bricksSet)) {
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


    }



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

    /** A brick's root view — every brick type has a render object that answers for itself. */
    @Nullable
    private View viewForBrick(BrickType type) {
        RenderBrick brick = renderBricks.get(type);
        return brick != null ? brick.view() : null;
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

    @Override
    public boolean isBrickHiddenByApp(@NonNull BrickType type) {
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

    /** True when at least one brick the user has in the row redraws on the minute tick. */
    private boolean anyBrickNeedsClockTick(Set<BrickType> order) {
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.needsClockTick() && order.contains(brick.type)) return true;
        }
        return false;
    }

    private void applyBrickVisibility(Set<BrickType> bricksSet) {
        if (binding == null) return;
        List<BrickTarget> targets = new ArrayList<>(renderBricks.size());
        for (RenderBrick brick : renderBricks.values()) {
            targets.add(resolveTarget(brick, bricksSet));
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
        // Deliberately NOT an "else" on the flip check: a composite brick's children keep
        // whatever it left behind while hidden, and applyBrickTarget only touches the root — so a
        // root flipping back to VISIBLE would come up empty until the next data callback. This
        // has to stay AFTER the flip check above, because refreshing content can set the root
        // VISIBLE itself, which would hide the flip from that comparison and cost the transition.
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.activeInLayout(bricksSet) && !isBrickHiddenByApp(brick.type)) {
                brick.onWillRender();
            }
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
        // excludeChildren keeps the brick's own root a target, so it still fades and re-bounds as
        // a unit; only its internals are off limits. TransitionSet forwards excludeTarget to the
        // transitions it holds but NOT excludeChildren, hence all three calls per brick.
        for (RenderBrick brick : renderBricks.values()) {
            View root = brick.view();
            if (!(root instanceof android.view.ViewGroup)) continue;
            changeBounds.excludeChildren(root, true);
            fade.excludeChildren(root, true);
            tx.excludeChildren(root, true);
        }
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
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.countsTowardFloor(bricks)) {
                h = Math.max(h, brick.minHeight());
            }
        }
        return h;
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
    public GnssProvider gnss() {
        return gnssProvider;
    }

    @NonNull
    @Override
    public Handler handler() {
        return mainHandler;
    }

    @NonNull
    @Override
    public Set<BrickType> currentOrder() {
        return currentBrickSet();
    }

    @Override
    public void onDestroy() {
        instance = null;

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        mainHandler.removeCallbacks(foregroundAppCheckRunnable);
        mainHandler.removeCallbacks(shrinkBufferSafetyClose);

        for (RenderBrick brick : renderBricks.values()) {
            brick.onDestroy();
        }
        // After the bricks: each drops its needs in onDestroy, and this is the backstop that
        // unregisters whatever a brick forgot to release.
        gnssProvider.destroy();

        if (binding != null && windowManager != null) {
            windowManager.removeView(binding.getRoot());
        }

        // Keep the process-wide car integration alive — the settings UI may still query
        // isBrickSupported after the overlay service stops. The sensor subscriptions themselves
        // are dropped by the temperature bricks in their own onDestroy.
        CarIntegrations.get(this).removeAvailabilityListener(carAvailabilityListener);
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
