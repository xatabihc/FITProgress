package ru.fitprogress.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** startForeground remains unconditional; ordinary updates/alerts respect Android 13 permission. */
fun Context.postNotificationIfAllowed(id: Int, notification: Notification) {
    if(Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
