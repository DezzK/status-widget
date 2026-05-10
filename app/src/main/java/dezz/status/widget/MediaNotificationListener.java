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

import android.service.notification.NotificationListenerService;

/**
 * Minimal NotificationListenerService that exists so the user can grant Notification Access to
 * this app. The component is then used by {@link android.media.session.MediaSessionManager} to
 * authorize calls to {@code getActiveSessions(ComponentName)}.
 */
public class MediaNotificationListener extends NotificationListenerService {
}
