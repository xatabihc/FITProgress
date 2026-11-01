package ru.fitprogress.ai

import android.app.*
import android.content.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** User-started workout clock and countdown signals, independent of Activity lifetime. */
class WorkoutTimerService : Service() {
    companion object {
        fun start(context: Context) { context.startForegroundService(Intent(context, WorkoutTimerService::class.java)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as FitApp
    private val repo get() = app.repository
    private var wake: PowerManager.WakeLock? = null
    private var current: Workout? = null
    private var job: Job? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("workout-clock", "Тренировка и таймеры", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(44, notification("Восстановление тренировки…"))
        if(job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            app.ready.await()
            val initial = repo.workouts.first()
            val sessions = repo.workouts.stateIn(this, SharingStarted.Eagerly, initial)
            try {
                var ticks = 0
                while(isActive) {
                    val w = sessions.value.firstOrNull { it.status in listOf("active", "paused") }
                    current = w
                    if(w == null) { stopSelf(); break }
                    val stamp = AndroidClock.read(this@WorkoutTimerService)
                    val rest = SessionTimers.rest(w, stamp); val stage = SessionTimers.stage(w, stamp)
                    val remaining = maxOf(rest, stage)
                    if(w.status == "active" && remaining > 0) {
                        if(wake?.isHeld != true) wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitProgress:Countdown").apply { acquire(remaining.coerceAtMost(86400000L) + 5000) }
                    } else releaseWake()
                    val timing = w.timing
                    val restDue = w.status == "active" && timing?.boot == stamp.boot && timing.restDeadlineMs > 0 && rest == 0L
                    val stageDue = w.status == "active" && timing?.boot == stamp.boot && timing.stageDeadlineMs > 0 && stage == 0L
                    if(restDue || stageDue) {
                        // Claim in Room before signalling; a restarted Activity cannot repeat the sound.
                        var claimed = false
                        repo.updateWorkout(w.id) { latest ->
                            val t = latest.timing
                            if(latest.status == "active" && t?.boot == stamp.boot) {
                                val rd = restDue && t.restDeadlineMs == timing?.restDeadlineMs
                                val sd = stageDue && t.stageDeadlineMs == timing?.stageDeadlineMs
                                claimed = rd || sd
                                latest.copy(timing = t.copy(restDeadlineMs = if(rd) 0 else t.restDeadlineMs, stageDeadlineMs = if(sd) 0 else t.stageDeadlineMs), restRemaining = if(rd) 0 else latest.restRemaining, timerRemaining = if(sd) 0 else latest.timerRemaining)
                            } else latest
                        }
                        if(claimed) {
                            val profile = repo.profile.first()
                            if(profile.timerVibration) (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                            if(profile.timerSound) { val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75); tone.startTone(ToneGenerator.TONE_PROP_BEEP, 700); scope.launch { try { delay(1000) } finally { tone.release() } } }
                            getSystemService(NotificationManager::class.java).notify(45, notification(if(stageDue) "Этап завершён — подтвердите подход" else "Отдых завершён", false))
                        }
                    }
                    if(++ticks % 5 == 0 && w.status == "active") repo.updateWorkout(w.id) { SessionTimers.checkpoint(it, stamp) }
                    val text = if(w.status == "paused") "Тренировка на паузе" else if(rest > 0) "Отдых ${formatTime(rest)}" else if(stage > 0) "Этап ${formatTime(stage)}" else "Тренировка ${formatTime(SessionTimers.elapsed(w, stamp))}"
                    getSystemService(NotificationManager::class.java).notify(44, notification(text))
                    delay(1000)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                current?.let { w -> runCatching { repo.updateWorkout(w.id) { SessionTimers.pause(it, AndroidClock.read(this@WorkoutTimerService)) } } }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 44, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "workout-clock").setSmallIcon(R.drawable.ic_logo).setContentTitle("FitProgress · тренировка").setContentText(text).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }
    private fun releaseWake() { if(wake?.isHeld == true) wake?.release(); wake = null }
    override fun onDestroy() { releaseWake(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
