package ru.fitprogress.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class RunService : Service() {
    companion object {
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"; const val FINISH = "finish"
        val signal = MutableStateFlow("Запись не запущена")
        val running = MutableStateFlow(false)
        fun command(context: Context, action: String) {
            val intent = Intent(context, RunService::class.java).setAction(action)
            if(action == START || action == RESUME) context.startForegroundService(intent) else context.startService(intent)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val repo get() = (application as FitApp).repository
    private lateinit var client: FusedLocationProviderClient
    private var current: Run? = null
    private var lastFix = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            events.trySend {
                val r = current
                if(r != null && r.status == "active") {
                    var next = r
                    result.locations.forEach { location ->
                        val point = GeoPoint(location.latitude, location.longitude, location.accuracy, location.time, next.segment)
                        val oldCount = next.points.size
                        next = Gps.append(next, point, System.currentTimeMillis())
                        if(next.points.size > oldCount) { lastFix = System.currentTimeMillis(); signal.value = "GPS · точность ±${location.accuracy.toInt()} м" }
                        else if(location.accuracy > 35) signal.value = "Низкая точность GPS · выйдите на открытое место"
                    }
                    current = next
                    if(next != r) repo.saveRun(next)
                }
            }
        }
        override fun onLocationAvailability(availability: LocationAvailability) { if(!availability.isLocationAvailable) signal.value = "GPS недоступен · ожидаем сигнал" }
    }
    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("run", "Запись пробежки", NotificationManager.IMPORTANCE_LOW))
        scope.launch { for(event in events) try { event() } catch(e: Exception) { signal.value = "Ошибка GPS: ${e.message ?: "не удалось записать"}"; pauseInternal(); } }
        scope.launch {
            while(isActive) {
                delay(5000)
                events.send {
                    current?.takeIf { it.status == "active" }?.let { r ->
                        val now = System.currentTimeMillis()
                        val next = r.copy(accumulatedMs = r.elapsed(now), activeSince = now)
                        current = next; repo.saveRun(next)
                        if(now - lastFix > 20000) signal.value = "Нет свежего сигнала GPS · расстояние не увеличивается"
                        getSystemService(NotificationManager::class.java).notify(42, notification(next))
                    }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground is immediate, before any database or permission work.
        if(intent?.action == START || intent?.action == RESUME) startForeground(42, notification(current))
        events.trySend {
            (application as FitApp).ready.await()
            when(intent?.action) {
                START, RESUME -> {
                    if(current?.status == "active") return@trySend
                    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { signal.value = "Нужна точная геолокация"; stopSelf(); return@trySend }
                    if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this@RunService) != ConnectionResult.SUCCESS) { signal.value = "Google Play Services недоступны на устройстве"; stopSelf(); return@trySend }
                    if(!getSystemService(LocationManager::class.java).isLocationEnabled) { signal.value = "Включите геолокацию в настройках телефона"; stopSelf(); return@trySend }
                    val old = current ?: repo.currentRun()
                    val now = System.currentTimeMillis()
                    current = old?.copy(status = "active", activeSince = now, segment = old.segment + 1) ?: Run()
                    repo.saveRun(current!!)
                    lastFix = now; signal.value = "Ожидаем точные координаты GPS"; running.value = true
                    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitProgress:Run").apply { acquire(12 * 60 * 60 * 1000L) }
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(5000).setWaitForAccurateLocation(true).build()
                    try { client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { error -> events.trySend { signal.value = "GPS: ${error.message}"; pauseInternal() } } }
                    catch(e: SecurityException) { signal.value = "Разрешение геолокации отозвано"; pauseInternal() }
                }
                PAUSE -> { if(current == null) current = repo.currentRun(); pauseInternal(); signal.value = "Пробежка на паузе" }
                FINISH -> {
                    if(current == null) current = repo.currentRun()
                    current?.takeIf { it.status != "done" }?.let { val now = System.currentTimeMillis(); val done = it.copy(status = "done", ended = now, accumulatedMs = it.elapsed(now), activeSince = 0); repo.saveRun(done); current = done }
                    client.removeLocationUpdates(callback); running.value = false; signal.value = "Пробежка сохранена"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
                else -> stopSelf()
            }
        }
        return START_NOT_STICKY // A killed service is recovered as paused, never as silently recording.
    }
    private suspend fun pauseInternal() {
        client.removeLocationUpdates(callback)
        current?.takeIf { it.status == "active" }?.let { val now = System.currentTimeMillis(); current = it.copy(status = "paused", accumulatedMs = it.elapsed(now), activeSince = 0, segment = it.segment + 1); repo.saveRun(current!!) }
        if(wakeLock?.isHeld == true) wakeLock?.release(); wakeLock = null; running.value = false
        current?.let { getSystemService(NotificationManager::class.java).notify(42, notification(it)) }
    }
    private fun notification(r: Run?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val finish = PendingIntent.getService(this, 2, Intent(this, RunService::class.java).setAction(FINISH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, RunService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "run").setSmallIcon(ru.fitprogress.ai.R.drawable.ic_logo).setContentTitle(if(r?.status == "paused") "Бег на паузе" else "FitProgress · запись GPS")
            .setContentText(if(r == null) "Подключение GPS…" else "%.2f км · %s".format(r.distance / 1000, formatTime(r.elapsed(System.currentTimeMillis())))).setContentIntent(open).setOngoing(true)
            .addAction(0, "Пауза", pause).addAction(0, "Сохранить", finish).build()
    }
    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        if(wakeLock?.isHeld == true) wakeLock?.release()
        running.value = false
        val id = current?.takeIf { it.status == "active" }?.id
        if(id != null) (application as FitApp).scope.launch { repo.updateRun(id) { it.copy(status = "paused", activeSince = 0, segment = it.segment + 1) } }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
