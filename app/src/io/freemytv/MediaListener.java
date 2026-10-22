package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Lets the karaoke screen see what's playing (MediaSessionManager.getActiveSessions needs an enabled notification
 * listener). It reads no notifications. Fire OS hides the setting, so it's turned on with adb (see KaraokeActivity).
 */
public class MediaListener extends NotificationListenerService {
}
