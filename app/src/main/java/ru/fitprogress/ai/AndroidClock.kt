package ru.fitprogress.ai

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

object AndroidClock {
    @Volatile private var boot: Long? = null
    fun read(context: Context): ClockStamp {
        val id = boot ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong() }.getOrDefault(-1).also { boot = it }
        return ClockStamp(System.currentTimeMillis(), SystemClock.elapsedRealtime(), id)
    }
}
