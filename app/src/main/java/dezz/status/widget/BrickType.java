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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The set of building blocks the user can place into the widget. Order is preserved as a
 * comma-separated string in {@link Preferences#brickOrder}; missing types are hidden.
 */
public enum BrickType {
    TIME, DATE, MEDIA, WIFI, GPS;

    @Nullable
    public static BrickType fromName(String name) {
        try {
            return BrickType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<BrickType> parseOrder(String csv) {
        List<BrickType> result = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return result;
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            BrickType type = fromName(trimmed);
            if (type != null && !result.contains(type)) {
                result.add(type);
            }
        }
        return result;
    }

    public static String serializeOrder(List<BrickType> bricks) {
        StringBuilder sb = new StringBuilder();
        for (BrickType b : bricks) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.name());
        }
        return sb.toString();
    }

    public static List<BrickType> all() {
        return new ArrayList<>(Arrays.asList(values()));
    }
}
