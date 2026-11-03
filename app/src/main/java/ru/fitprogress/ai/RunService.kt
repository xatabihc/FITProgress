package ru.fitprogress.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.os.*
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.first
import androidx.core.app.NotificationCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class RunService : Service() {
    companion object {
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"; const val FINISH = "finish"; const val UPDATE_ROUTE = "update-route"; private const val EXTRA_ROUTE = "navigation-route"
        val launch = RunLaunchController()
        val signal = MutableStateFlow("Запись не запущена")
        val running = MutableStateFlow(false)
        fun command(context: Context, action: String, route: NavigationRoute? = null) {
            val intent = Intent(context, RunService::class.java).setAction(action)
            if(route != null) intent.putExtra(EXTRA_ROUTE, BackupCodec.gson.toJson(route))
            Log.i("FitRunStart", "Dispatch service action=$action")
            try {
                if(action == START || action == RESUME) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            } catch(e: RuntimeException) { Log.e("FitRunStart", "Service dispatch rejected action=$action", e); throw e }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val repo get() = (application as FitApp).repository
    private var client: FusedLocationProviderClient? = null
    private var usingPlatformGps = false
    private val locations get() = getSystemService(LocationManager::class.java)
    private val platformListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { acceptLocations(listOf(location)) }
        override fun onProviderDisabled(provider: String) { events.trySend { pauseInternal(); signal.value = "GPS выключен"; launch.fail(RunLaunchError.LOCATION_DISABLED) } }
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    private var current: Run? = null
    private var lastFix = 0L
    private val deviationMonitor = RouteDeviationMonitor()
    private val rerouteGate = AutoRerouteGate()
    private var monitoredRunId: String? = null
    private var wakeUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { if(!usingPlatformGps) acceptLocations(result.locations) }
        override fun onLocationAvailability(availability: LocationAvailability) { if(!availability.isLocationAvailable) signal.value = "GPS недоступен · ожидаем сигнал" }
    }
    private fun acceptLocations(fixes: List<Location>) {
            events.trySend {
                val r = current
                if(r != null && r.status == "active") {
                    var next: Run = r
                    fixes.forEach { location ->
                        if(!location.hasAccuracy()) { signal.value = "GPS без оценки точности: точка отклонена"; return@forEach }
                        if(location.isFromMockProvider) { signal.value = "Тестовые координаты Android не входят в реальную историю GPS"; return@forEach }
                        val stamp = AndroidClock.read(this@RunService)
                        val point = GeoPoint(location.latitude, location.longitude, location.accuracy, location.time, next.segment, location.elapsedRealtimeNanos / 1_000_000L, stamp.boot)
                        val oldCount = next.points.size
                        next = Gps.appendAt(next, point, stamp)
                        if(Gps.valid(point) && Gps.age(point, stamp) in 0L..20000L) { lastFix = android.os.SystemClock.elapsedRealtime(); signal.value = "GPS · ±${location.accuracy.toInt()} м · фильтрация шума" }
                        if(next.points.size > oldCount) {
                            lastFix = android.os.SystemClock.elapsedRealtime(); signal.value = "GPS · точность ±${location.accuracy.toInt()} м"
                            val navigation = next.navigation
                            if(navigation != null) {
                                if(monitoredRunId != next.id) { monitoredRunId = next.id; deviationMonitor.reset(); rerouteGate.reset() }
                                val accepted = next.points.last()
                                val progress = deviationMonitor.update(navigation, RouteCoordinate(accepted.lat, accepted.lon), accepted.accuracy)
                                if(progress?.offRoute == true) {
                                    signal.value = "Отклонение подтверждено GPS · готовим перестроение"
                                    requestAutomaticReroute(next, navigation, RouteCoordinate(accepted.lat, accepted.lon))
                                }
                            }
                        }
                        else if(location.accuracy > 35) signal.value = "Низкая точность GPS · выйдите на открытое место"
                    }
                    val km = (next.distance / 1000).toInt()
                    val announce = km > next.lastAnnouncedKm
                    next = next.copy(lastAnnouncedKm = maxOf(next.lastAnnouncedKm, km))
                    current = next
                    if(next != r) repo.saveRun(next)
                    if(announce && repo.profile.first().runSignals) {
                        val p = repo.profile.first()
                        if(p.timerVibration) (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                        if(p.timerSound) { val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75); tone.startTone(ToneGenerator.TONE_PROP_BEEP, 500); scope.launch { try { delay(700) } finally { tone.release() } } }
                        signal.value = "Отсечка: $km км GPS"
                    }
                }
            }
    }
    override fun onCreate() {
        super.onCreate()
        Log.i("FitRunStart", "Service onCreate")
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("run", "Запись пробежки", NotificationManager.IMPORTANCE_LOW))
        scope.launch { for(event in events) try { event() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.e("FitRunStart", "Service event failed", e); launch.fail(if(e is SecurityException) RunLaunchError.PERMISSION else RunLaunchError.STORAGE, e.javaClass.simpleName); signal.value = launch.state.value.error!!.message; runCatching { pauseInternal() }; stopSelf() } }
        scope.launch {
            while(isActive) {
                delay(5000)
                events.send {
                    current?.takeIf { it.status == "active" }?.let { r ->
                        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !getSystemService(LocationManager::class.java).isLocationEnabled) { signal.value = "Геолокация отключена или разрешение отозвано"; pauseInternal(); launch.fail(if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) RunLaunchError.PERMISSION else RunLaunchError.LOCATION_DISABLED); return@send }
                        acquireRunWakeLock()
                        val next = RunTimers.checkpoint(r, AndroidClock.read(this@RunService))
                        current = next; repo.saveRun(next)
                        if(android.os.SystemClock.elapsedRealtime() - lastFix > 20000) signal.value = "Нет свежего сигнала GPS · расстояние не увеличивается"
                        postNotificationIfAllowed(42, notification(next))
                    }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("FitRunStart", "Service onStartCommand action=${intent?.action} startId=$startId")
        // startForeground is immediate, before any database or permission work.
        if(intent?.action == START || intent?.action == RESUME) {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { launch.fail(RunLaunchError.PERMISSION); signal.value = RunLaunchError.PERMISSION.message; stopSelf(); return START_NOT_STICKY }
            try { startForeground(42, notification(current)) } catch(e: RuntimeException) { Log.e("FitRunStart", "startForeground failed", e); launch.fail(if(e is SecurityException) RunLaunchError.PERMISSION else RunLaunchError.SERVICE, e.javaClass.simpleName); signal.value = if(e is SecurityException) "Разрешение геолокации отозвано" else "Фоновая запись недоступна. Откройте приложение и повторите запуск."; stopSelf(); return START_NOT_STICKY }
        }
        events.trySend {
            (application as FitApp).ready.await()
            when(intent?.action) {
                START, RESUME -> {
                    if(current?.status == "active") { launch.active(); return@trySend }
                    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { launch.fail(RunLaunchError.PERMISSION); signal.value = RunLaunchError.PERMISSION.message; stopSelf(); return@trySend }
                    if(!getSystemService(LocationManager::class.java).isLocationEnabled) { launch.fail(RunLaunchError.LOCATION_DISABLED); signal.value = RunLaunchError.LOCATION_DISABLED.message; stopSelf(); return@trySend }
                    val requestedRoute = intent?.getStringExtra(EXTRA_ROUTE)?.let { BackupCodec.gson.fromJson(it, NavigationRoute::class.java).also(RouteMath::validate) }
                    val old = current?.takeIf { it.status != "done" } ?: repo.currentRun()
                    val base = old ?: Run(status = "paused", activeSince = 0)
                    current = RunTimers.resume(base.copy(navigation = requestedRoute ?: base.navigation), AndroidClock.read(this@RunService))
                    if(monitoredRunId != current!!.id) { monitoredRunId = current!!.id; deviationMonitor.reset(); rerouteGate.reset() }
                    repo.saveRun(current!!)
                    lastFix = android.os.SystemClock.elapsedRealtime(); signal.value = "Ожидаем точные координаты GPS"; running.value = true
                    acquireRunWakeLock()
                    try {
                        subscribeLocations()
                        launch.active()
                        Log.i("FitRunStart", "Run active; waiting for real GPS fix")
                    } catch(e: Exception) {
                        Log.e("FitRunStart", "Location subscription failed", e)
                        pauseInternal()
                        launch.fail(if(e is SecurityException) RunLaunchError.PERMISSION else if(e is IllegalArgumentException) RunLaunchError.NO_PROVIDER else RunLaunchError.SUBSCRIPTION, e.javaClass.simpleName)
                        signal.value = launch.state.value.error!!.message
                    }
                }
                PAUSE -> { if(current == null) current = repo.currentRun(); pauseInternal(); launch.idle(); signal.value = "Пробежка на паузе" }
                UPDATE_ROUTE -> {
                    if(current == null) current = repo.currentRun()
                    val route = intent?.getStringExtra(EXTRA_ROUTE)?.let { BackupCodec.gson.fromJson(it, NavigationRoute::class.java).also(RouteMath::validate) } ?: error("Новый маршрут не передан")
                    val run = current?.takeIf { it.status != "done" } ?: error("Нет активной пробежки")
                    current = run.copy(navigation = route); repo.saveRun(current!!); signal.value = "Маршрут навигации обновлён"
                    deviationMonitor.reset()
                }
                FINISH -> {
                    if(current == null) current = repo.currentRun()
                    current?.takeIf { it.status != "done" }?.let { val done = RunTimers.finish(it, AndroidClock.read(this@RunService)); repo.saveRun(done); current = done }
                    removeUpdates(); launch.idle(); running.value = false; signal.value = "Пробежка сохранена"; if(stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
                else -> stopSelf()
            }
        }
        return START_NOT_STICKY // A killed service is recovered as paused, never as silently recording.
    }
    private fun requestAutomaticReroute(run: Run, oldRoute: NavigationRoute, from: RouteCoordinate) {
        if(!rerouteGate.tryBegin(android.os.SystemClock.elapsedRealtime())) return
        val destination = oldRoute.points.lastOrNull() ?: run { rerouteGate.finish(); return }
        Log.i("FitRunNavigation", "Automatic reroute requested run=${run.id} route=${oldRoute.id}")
        scope.launch {
            try {
                val profile = repo.profile.first()
                val backend = ValhallaRoutingBackend(profile.routingBackendUrl)
                val route = backend.route(RouteRequest(listOf(from, destination), RouteMode.valueOf(oldRoute.mode))).minByOrNull { it.distanceMeters }
                    ?: throw RoutingException("Сервер не вернул маршрут", "no_route")
                events.send {
                    rerouteGate.finish()
                    val latest = current
                    if(latest?.id == run.id && latest.status == "active" && latest.navigation?.id == oldRoute.id) {
                        val updated = route.copy(name = "Перестроенный · ${RouteMode.valueOf(oldRoute.mode).label}")
                        current = latest.copy(navigation = updated); repo.saveRun(current!!); deviationMonitor.reset()
                        signal.value = "Маршрут перестроен · %.2f км осталось".format(updated.distanceMeters / 1000)
                        Log.i("FitRunNavigation", "Automatic reroute applied run=${run.id} distance=${updated.distanceMeters.toInt()}m")
                    }
                }
            } catch(e: CancellationException) { rerouteGate.finish(); throw e }
            catch(e: Exception) {
                Log.w("FitRunNavigation", "Automatic reroute failed; GPS continues", e)
                events.trySend { rerouteGate.finish(); signal.value = if(e is RoutingException && e.category == "network") "Нет сети или сервер недоступен · запись GPS продолжается" else "Не удалось перестроить маршрут · запись GPS продолжается" }
            }
        }
    }
    private fun subscribeLocations() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Точное разрешение геолокации отозвано перед подпиской")
        }
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        Log.i("FitRunStart", "Location backend=${if(gms) "fused" else "android-gps"}")
        if(gms) {
            val fused = LocationServices.getFusedLocationProviderClient(this); client = fused
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(5000).setWaitForAccurateLocation(true).build()
            usingPlatformGps = false
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { error -> events.trySend {
                if(current?.status == "active" && !usingPlatformGps) {
                    Log.w("FitRunStart", "Fused request failed; checking Android GPS fallback", error)
                    val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val gpsPresent = locations.allProviders.contains(LocationManager.GPS_PROVIDER)
                    val gpsEnabled = gpsPresent && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    if(GpsFallbackPolicy.canUsePlatformGps(hasFine, gpsPresent, gpsEnabled, usingPlatformGps)) {
                        try {
                            client?.removeLocationUpdates(callback)
                            client = null
                            subscribePlatformGps()
                            signal.value = "Системный GPS Android подключён · запись продолжается"
                            Log.i("FitRunStart", "Switched to platform GPS without pausing run")
                        } catch(fallbackError: Exception) {
                            Log.e("FitRunStart", "Android GPS fallback failed", fallbackError)
                            pauseInternal()
                            launch.fail(if(fallbackError is SecurityException) RunLaunchError.PERMISSION else RunLaunchError.SUBSCRIPTION, fallbackError.javaClass.simpleName)
                            signal.value = launch.state.value.error!!.message
                        }
                    } else {
                        pauseInternal()
                        launch.fail(if(!hasFine) RunLaunchError.PERMISSION else if(!gpsPresent || !gpsEnabled) RunLaunchError.NO_PROVIDER else RunLaunchError.SUBSCRIPTION, error.javaClass.simpleName)
                        signal.value = launch.state.value.error!!.message
                    }
                }
            } }
        } else {
            subscribePlatformGps()
        }
    }
    private fun subscribePlatformGps() {
        require(locations.allProviders.contains(LocationManager.GPS_PROVIDER) && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "GPS_PROVIDER unavailable" }
        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, platformListener, Looper.getMainLooper())
        usingPlatformGps = true
    }
    private fun removeUpdates() {
        try { client?.removeLocationUpdates(callback) } catch(e: SecurityException) { Log.w("FitRunStart", "Permission revoked during fused cleanup", e) }
        try { locations.removeUpdates(platformListener) } catch(e: SecurityException) { Log.w("FitRunStart", "Permission revoked during GPS cleanup", e) }
        usingPlatformGps = false
        client = null
    }
    private fun acquireRunWakeLock() {
        val now = android.os.SystemClock.elapsedRealtime()
        if(wakeLock?.isHeld == true && now < wakeUntil - 30000) return
        if(wakeLock?.isHeld == true) wakeLock?.release()
        wakeUntil = now + 60 * 60 * 1000L
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitProgress:Run").apply { acquire(60 * 60 * 1000L) }
    }
    private suspend fun pauseInternal() {
        removeUpdates()
        current?.takeIf { it.status == "active" }?.let { current = RunTimers.pause(it, AndroidClock.read(this@RunService)); repo.saveRun(current!!) }
        if(wakeLock?.isHeld == true) wakeLock?.release(); wakeLock = null; running.value = false
        current?.let { postNotificationIfAllowed(42, notification(it)) }
    }
    private fun notification(r: Run?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val finish = PendingIntent.getService(this, 2, Intent(this, RunService::class.java).setAction(FINISH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = r?.status == "paused"
        val control = PendingIntent.getService(this, 1, Intent(this, RunService::class.java).setAction(if(paused) RESUME else PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "run").setSmallIcon(ru.fitprogress.ai.R.drawable.ic_logo).setContentTitle(if(paused) "Бег на паузе" else "FitProgress · запись GPS")
            .setContentText(if(r == null) "Подключение GPS…" else "%.2f км · %s".format(r.distance / 1000, formatTime(RunTimers.elapsed(r, AndroidClock.read(this))))).setContentIntent(open).setOngoing(true)
            .addAction(0, if(paused) "Продолжить" else "Пауза", control).addAction(0, "Сохранить", finish).build()
    }
    override fun onDestroy() {
        removeUpdates()
        if(wakeLock?.isHeld == true) wakeLock?.release()
        running.value = false
        val previous = current?.takeIf { it.status == "active" }
        if(previous != null) (application as FitApp).scope.launch { repo.updateRun(previous.id) { if(it.segment == previous.segment) RunTimers.pause(it, AndroidClock.read(this@RunService)) else it } }
        if(launch.state.value.phase == RunLaunchPhase.ACTIVE || launch.state.value.pending) { launch.fail(RunLaunchError.SERVICE, "ServiceDestroyed") }
        Log.i("FitRunStart", "Service onDestroy")
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
