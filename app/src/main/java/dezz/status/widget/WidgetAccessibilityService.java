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

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accessibility service whose sole purpose is to report, for each physical display, which
 * app package is currently in the foreground there. Needed because Geely Monjaro head units
 * run 4 displays in parallel: a user-app switch on display 2 must not change overlay
 * visibility on display 1 (and vice versa), but {@link android.app.usage.UsageStatsManager}
 * doesn't expose display IDs — its events are global. {@link AccessibilityWindowInfo} does.
 * <p>
 * The service is intentionally a thin reporter: it tracks the active package per display
 * and notifies the {@link WidgetService} singleton, which decides what to do based on the
 * display its own overlay window lives on. Disabling this accessibility service falls back
 * to the original single-display behaviour via {@link android.app.usage.UsageStatsManager}.
 */
public class WidgetAccessibilityService extends AccessibilityService {
    private static final String TAG = "WidgetA11yService";

    @Nullable
    private static volatile WidgetAccessibilityService instance;

    /** displayId → current foreground package. Updated on every window change event. */
    private final Map<Integer, String> foregroundByDisplay = new HashMap<>();

    @Nullable
    public static WidgetAccessibilityService getInstance() {
        return instance;
    }

    /**
     * @param displayId numeric display ID (matches {@link android.view.Display#getDisplayId()}).
     * @return foreground package on that display, or {@code null} if we haven't seen one
     *         (display absent / no window event observed yet).
     */
    @Nullable
    public String getForegroundPackageOnDisplay(int displayId) {
        synchronized (foregroundByDisplay) {
            return foregroundByDisplay.get(displayId);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
        }
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            // Falling out of accessibility-driven tracking — let WidgetService refresh the
            // tracking pipeline (which falls back to UsageStatsManager polling).
            widget.onForegroundTrackingPathChanged();
        }
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Initial seed: walk all windows currently known to the accessibility framework and
        // remember the per-display foreground packages. Otherwise the first event-driven
        // update would have to wait for a real window change.
        seedFromCurrentWindows();
        Log.i(TAG, "Connected. Seeded " + foregroundByDisplay.size() + " display(s).");
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundTrackingPathChanged();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        // After any window state change, re-scan: the event itself carries one package, but
        // we want a coherent snapshot of every display, not just the one that changed. Cheap
        // — there are typically only a handful of accessibility windows in total.
        seedFromCurrentWindows();

        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundDisplayMapUpdated();
        }
    }

    @Override
    public void onInterrupt() {
        // No-op — we don't drive any feedback streams.
    }

    /**
     * Refreshes {@link #foregroundByDisplay} from the live list of accessibility windows.
     * <p>
     * On API 30+ we use {@link #getWindowsOnAllDisplays()} which returns a
     * {@code SparseArray<List<AccessibilityWindowInfo>>} keyed by display ID. Below that we
     * fall back to {@link #getWindows()} (single-display only) — the per-display behaviour
     * matters only on multi-display devices, which all run Android 10+/Auto so this fallback
     * is just for completeness.
     */
    private void seedFromCurrentWindows() {
        Map<Integer, String> next = new HashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                String pkg = topApplicationPackage(all.valueAt(i));
                if (pkg != null) next.put(displayId, pkg);
            }
        } else {
            List<AccessibilityWindowInfo> windows = getWindows();
            String pkg = topApplicationPackage(windows);
            if (pkg != null) next.put(android.view.Display.DEFAULT_DISPLAY, pkg);
        }
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
            foregroundByDisplay.putAll(next);
        }
    }

    /**
     * Picks the topmost application window (not system / IME / accessibility overlay) from a
     * list of {@link AccessibilityWindowInfo}, and returns its package name via the root
     * AccessibilityNodeInfo. Higher layer = newer, so we walk in descending z-order.
     */
    @Nullable
    private static String topApplicationPackage(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        AccessibilityWindowInfo best = null;
        int bestLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            int layer = w.getLayer();
            if (layer > bestLayer) {
                bestLayer = layer;
                best = w;
            }
        }
        if (best == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root = best.getRoot();
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } finally {
            root.recycle();
        }
    }
}
