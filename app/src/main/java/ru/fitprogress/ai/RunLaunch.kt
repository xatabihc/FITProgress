package ru.fitprogress.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunLaunchPhase { IDLE, PERMISSION, STARTING, ACTIVE, ERROR }
enum class RunLaunchError(val message: String) {
    PERMISSION("Для записи маршрута нужна точная геолокация. Разрешите её в системном запросе или настройках приложения."),
    LOCATION_DISABLED("Геолокация выключена. Включите её в настройках телефона и повторите запуск."),
    NO_PROVIDER("GPS-провайдер недоступен на этом устройстве. Проверьте настройки геолокации."),
    SERVICE("Android не разрешил запуск записи. Откройте приложение, проверьте разрешения и повторите попытку."),
    STORAGE("Не удалось сохранить начало пробежки. История не удалялась. Проверьте свободное место и перезапустите приложение."),
    SUBSCRIPTION("Не удалось подключиться к геолокации. Проверьте разрешение и повторите попытку."),
    TIMEOUT("Сервис не подтвердил запуск за 15 секунд. Повторите попытку; если ошибка повторится, перезапустите приложение.")
}
data class RunLaunchState(val phase: RunLaunchPhase = RunLaunchPhase.IDLE, val error: RunLaunchError? = null, val diagnostic: String = "", val attempt: Long = 0) {
    val pending get() = phase == RunLaunchPhase.PERMISSION || phase == RunLaunchPhase.STARTING
}
/** Whether a failed Fused Location subscription may safely switch to the Android GPS provider. */
object GpsFallbackPolicy {
    fun canUsePlatformGps(finePermission: Boolean, gpsPresent: Boolean, gpsEnabled: Boolean, alreadyUsingPlatform: Boolean): Boolean =
        finePermission && gpsPresent && gpsEnabled && !alreadyUsingPlatform
}

/** Shared launch handshake; no GPS coordinates or private history in diagnostic messages. */
class RunLaunchController {
    private val attempts = java.util.concurrent.atomic.AtomicLong()
    private val mutable = MutableStateFlow(RunLaunchState())
    val state = mutable.asStateFlow()
    fun request(fine: Boolean): Boolean {
        while(true) {
            val old = mutable.value
            if(old.pending || old.phase == RunLaunchPhase.ACTIVE) return false
            if(mutable.compareAndSet(old, RunLaunchState(if(fine) RunLaunchPhase.STARTING else RunLaunchPhase.PERMISSION, attempt = attempts.incrementAndGet()))) return true
        }
    }
    fun permissionResult(fine: Boolean): Boolean {
        if(mutable.value.phase != RunLaunchPhase.PERMISSION) return false
        if(!fine) { fail(RunLaunchError.PERMISSION); return false }
        return mutable.compareAndSet(mutable.value, RunLaunchState(RunLaunchPhase.STARTING, attempt = mutable.value.attempt))
    }
    fun dispatch(locationEnabled: Boolean, startService: () -> Unit): Boolean {
        if(mutable.value.phase != RunLaunchPhase.STARTING) return false
        if(!locationEnabled) { fail(RunLaunchError.LOCATION_DISABLED); return false }
        return try { startService(); true } catch(e: RuntimeException) { fail(RunLaunchError.SERVICE, e.javaClass.simpleName); false }
    }
    fun active() { mutable.value = RunLaunchState(RunLaunchPhase.ACTIVE, attempt = mutable.value.attempt) }
    fun idle() { mutable.value = RunLaunchState() }
    fun fail(error: RunLaunchError, diagnostic: String = "") { mutable.value = RunLaunchState(RunLaunchPhase.ERROR, error, diagnostic, mutable.value.attempt) }
    fun timeout(attempt: Long) { val old = mutable.value; if(old.phase == RunLaunchPhase.STARTING && old.attempt == attempt) mutable.compareAndSet(old, RunLaunchState(RunLaunchPhase.ERROR, RunLaunchError.TIMEOUT, attempt = old.attempt)) }
}
