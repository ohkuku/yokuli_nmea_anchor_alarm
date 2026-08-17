package com.yokuli.anchorwatch.runtime

import android.app.Notification

/** Android lifecycle operations deliberately kept outside the ordered runtime coordinator. */
interface RuntimeServiceHost {
    fun notificationPermissionGranted(): Boolean
    fun startForeground(notification: Notification, location: Boolean): Boolean
    fun stopForegroundAndSelf()
}
