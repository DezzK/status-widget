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

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;
import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * A one-line reading fed by the flavor's {@link CarIntegration}, with an optional icon in front
 * of it. Everything the car bricks share lives here: gating on vehicle support, declaring the
 * metrics to the integration, a placeholder until the first value, the icon, and repainting from
 * the last values whenever a preference changes how they are shown — a fuel gauge can sit on one
 * value for many minutes, so waiting for the next reading to apply a new unit is not an option.
 *
 * <h3>Parts</h3>
 * The brick's root is a container ({@code overlay_reading_brick.xml}) holding the icon and the
 * text. The brick's own look styles the text, except its margins, which belong to the root; the
 * icon has a look of its own ({@link Preferences.InlineIconPrefs}). Each part carries its own
 * opacity, so the root's stays at 1 — see {@link #contentAlpha()}.
 */
abstract class CarTextRenderBrick extends TextRenderBrick implements CarIntegration.Listener {

    /**
     * How a reading stands, as the color its icon takes in the colored icon style — the role a
     * connection state plays for the status icons.
     */
    enum ReadingState {
        /** Nothing to show yet. */
        NO_DATA(R.color.status_off),
        /** A value with no good or bad to it, such as a temperature: drawn like the text. */
        NEUTRAL(R.color.text_primary),
        OK(R.color.status_ok),
        WARNING(R.color.status_warning),
        CRITICAL(R.color.status_error);

        @ColorRes
        final int color;

        ReadingState(@ColorRes int color) {
            this.color = color;
        }
    }

    private final Preferences.CarTextBrickPrefs prefs;
    /** One row per icon design, in the order of the {@code icon_designs} array. */
    private final int[][] iconDesigns;
    private final IconPart icon;
    private ViewGroup root;

    /** Last value per metric since the brick became active. */
    private final EnumMap<CarMetric, Float> latest = new EnumMap<>(CarMetric.class);

    /**
     * @param rootId        the brick's include in the overlay layout
     * @param iconDrawables the brick's icon in each icon design, in {@code icon_designs} order
     */
    protected CarTextRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                                 @NonNull Preferences.CarTextBrickPrefs prefs, @IdRes int rootId,
                                 @NonNull int... iconDrawables) {
        super(host, type, prefs, rootId);
        this.prefs = prefs;
        // A reading's icon does not change with its state — the state only colors it — so each
        // design row holds the one drawable.
        this.iconDesigns = new int[iconDrawables.length][];
        for (int i = 0; i < iconDrawables.length; i++) {
            iconDesigns[i] = new int[]{iconDrawables[i]};
        }
        this.icon = new IconPart(host);
    }

    /** Shown while there is nothing to format yet. */
    @NonNull
    protected abstract String placeholder();

    /** The text for the values received so far, or {@code null} while they are not enough. */
    @Nullable
    protected abstract String format(@NonNull EnumMap<CarMetric, Float> latest);

    /**
     * How the values received so far stand, for the icon's color. Asked only when
     * {@link #format} produced a text. A reading with no good or bad to it keeps the default.
     */
    @NonNull
    protected ReadingState readingState(@NonNull EnumMap<CarMetric, Float> latest) {
        return ReadingState.NEUTRAL;
    }

    /**
     * Everything this brick wants delivered: its required metrics plus any it merely makes use
     * of. Asked on every settings pass, so it may depend on preferences.
     */
    @NonNull
    protected Set<CarMetric> neededCarMetrics() {
        return type.requiredCarMetrics();
    }

    @Override
    void bind(@NonNull OverlayStatusWidgetBinding binding) {
        // The view id is the include's: for a reading, the brick's view is the container.
        root = binding.getRoot().findViewById(viewId);
        text = root.findViewById(R.id.readingText);
        icon.bind(root.findViewById(R.id.readingIcon));
    }

    @NonNull
    @Override
    View view() {
        return root;
    }

    /** The parts carry their own opacity; the root multiplying it again would dim both twice. */
    @Override
    float contentAlpha() {
        return 1f;
    }

    @Override
    void applySettings() {
        super.applySettings();
        OutlineImageView iconView = icon.view();
        if (iconView == null) return;
        boolean shown = prefs.icon.show.get();
        iconView.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) icon.applyLook(prefs.icon.look);
    }

    @Override
    int minHeight() {
        int textHeight = super.minHeight();
        if (!prefs.icon.show.get()) return textHeight;
        return Math.max(textHeight, prefs.icon.look.size.get());
    }

    /**
     * A preset imported from another car may list a metric this vehicle cannot feed. Such a brick
     * must neither render — it would be a permanently frozen placeholder — nor raise the height
     * floor for something that never appears.
     */
    @Override
    boolean activeInLayout(@NonNull Set<BrickType> order) {
        return order.contains(type) && car().areMetricsSupported(type.requiredCarMetrics());
    }

    @Override
    boolean countsTowardFloor(@NonNull Set<BrickType> order) {
        return activeInLayout(order);
    }

    @Override
    void syncSource(boolean active) {
        if (!hasView()) return;
        if (active) {
            // Declare the needs regardless of support: right after boot the vendor service may
            // not have connected yet and support reads as "unknown/error" — but the SDK queues
            // listener registrations locally, so declaring now means data starts flowing the
            // moment the service comes up. Visibility is gated separately, and the
            // availability-changed callback re-runs the settings pass when the answer flips.
            car().setNeeds(this, neededCarMetrics());
            render();
        } else {
            car().setNeeds(this, EnumSet.noneOf(CarMetric.class));
            // Reset so a re-added brick starts from the placeholder, not a stale reading.
            latest.clear();
            render();
        }
    }

    @Override
    void refreshContent() {
        render();
    }

    @Override
    void onConfigurationChanged() {
        render();
    }

    @Override
    public void onCarValue(@NonNull CarMetric metric, float value) {
        latest.put(metric, value);
        render();
    }

    @Override
    void onDestroy() {
        car().setNeeds(this, EnumSet.noneOf(CarMetric.class));
    }

    private void render() {
        if (!hasView()) return;
        String formatted = format(latest);
        // Hot path: temperature arrives about once a second and usually rounds to the same
        // integer for minutes on end. An unconditional setText would relayout the whole row at
        // that cadence. Placeholder until the first value, so the brick occupies its slot instead
        // of rendering as a zero-width hole.
        setTextIfChanged(formatted != null ? formatted : placeholder());
        if (prefs.icon.show.get()) {
            ReadingState state = formatted != null ? readingState(latest) : ReadingState.NO_DATA;
            icon.paint(icon.drawableFor(iconDesigns, 0), state.color, prefs.icon.look);
        }
    }

    protected final CarIntegration car() {
        return CarIntegrations.get(host.context());
    }
}
