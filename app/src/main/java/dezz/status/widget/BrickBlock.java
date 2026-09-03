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

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.Map;

import static dezz.status.widget.SwitchListBlockBinder.row;

/**
 * The registry of per-brick settings blocks: one constant per brick that has a block of its own,
 * each pairing the block's layout with the binder that reads it. Keeping the two in one constant
 * is what makes them impossible to desync.
 *
 * <p>This is the settings screen's own map, deliberately NOT a hook on {@link Preferences} — the
 * preference model has no business naming views. It is also the reason no cast appears anywhere:
 * each constant reaches for the concrete prefs field it needs ({@code prefs.date}, {@code
 * prefs.media}, …), which is already statically typed.
 *
 * <p>A brick with no block simply has no constant here.
 */
enum BrickBlock {
    DATE(BrickType.DATE, R.layout.brick_block_date) {
        @Override
        BrickBlockBinder newBinder(BrickBinderContext ctx, View root) {
            return new DateBlockBinder(ctx, root, ctx.prefs.date);
        }
    },
    GPS(BrickType.GPS, R.layout.brick_block_gps) {
        @Override
        BrickBlockBinder newBinder(BrickBinderContext ctx, View root) {
            return new SwitchListBlockBinder(ctx, root,
                    row(R.id.brickGpsShowSatelliteBadge, ctx.prefs.gps.showSatelliteBadge));
        }
    },
    BLUETOOTH(BrickType.BLUETOOTH, R.layout.brick_block_bluetooth) {
        @Override
        BrickBlockBinder newBinder(BrickBinderContext ctx, View root) {
            return new SwitchListBlockBinder(ctx, root,
                    row(R.id.brickBluetoothShowDeviceCountBadge,
                            ctx.prefs.bluetooth.showDeviceCountBadge));
        }
    },
    MEDIA(BrickType.MEDIA, R.layout.brick_block_media) {
        @Override
        BrickBlockBinder newBinder(BrickBinderContext ctx, View root) {
            return new MediaBlockBinder(ctx, root, ctx.prefs.media);
        }
    },
    GNSS_INFO(BrickType.GNSS_INFO, R.layout.brick_block_gnss_info) {
        @Override
        BrickBlockBinder newBinder(BrickBinderContext ctx, View root) {
            return new SwitchListBlockBinder(ctx, root,
                    row(R.id.brickGnssInfoShowSatellites, ctx.prefs.gnssInfo.showSatellites),
                    row(R.id.brickGnssInfoSatellitesUsedInFix,
                            ctx.prefs.gnssInfo.satellitesUsedInFix),
                    row(R.id.brickGnssInfoShowMode, ctx.prefs.gnssInfo.showMode),
                    row(R.id.brickGnssInfoShowRoad, ctx.prefs.gnssInfo.showRoad),
                    row(R.id.brickGnssInfoShowAccuracy, ctx.prefs.gnssInfo.showAccuracy),
                    row(R.id.brickGnssInfoShowSpeed, ctx.prefs.gnssInfo.showSpeed),
                    row(R.id.brickGnssInfoShowAltitude, ctx.prefs.gnssInfo.showAltitude),
                    row(R.id.brickGnssInfoShowFixAge, ctx.prefs.gnssInfo.showFixAge));
        }
    };

    private static final Map<BrickType, BrickBlock> BY_TYPE = new EnumMap<>(BrickType.class);

    static {
        for (BrickBlock block : values()) {
            BY_TYPE.put(block.type, block);
        }
    }

    private final BrickType type;
    @LayoutRes
    final int layoutRes;

    BrickBlock(BrickType type, @LayoutRes int layoutRes) {
        this.type = type;
        this.layoutRes = layoutRes;
    }

    abstract BrickBlockBinder newBinder(BrickBinderContext ctx, View root);

    /** The block belonging to a brick, or {@code null} if it has none. */
    @Nullable
    static BrickBlock forType(BrickType type) {
        return BY_TYPE.get(type);
    }
}
