package ru.fitprogress.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.*
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.*

@Composable fun AdditionalSettings(vm: FitViewModel) {
    val p by vm.profile.collectAsStateWithLifecycle(); var hour by rememberSaveable(p.reminderHour) { mutableStateOf(p.reminderHour.toString()) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enabled -> vm.action { vm.repo.updateProfile { it.copy(reminders = enabled) } }; if(!enabled) vm.notify("Напоминания не включены: уведомления Android не разрешены") }
    Panel {
        Text("Интерфейс и напоминания", style = MaterialTheme.typography.titleLarge)
        Choice(listOf("Лайм", "Голубой", "Оранжевый"), when(p.accent) { "cyan" -> "Голубой"; "orange" -> "Оранжевый"; else -> "Лайм" }) { label -> vm.action { vm.repo.updateProfile { it.copy(accent = when(label) { "Голубой" -> "cyan"; "Оранжевый" -> "orange"; else -> "lime" }) } } }
        SettingToggle("Показывать интернет-карту", p.mapEnabled) { value -> vm.action { vm.repo.updateProfile { it.copy(mapEnabled = value) } } }
        Text("При отключённой карте маршрут виден на локальной схеме без загрузки тайлов.", style = MaterialTheme.typography.bodySmall)
        SettingToggle("Напоминать о плане на сегодня", p.reminders) { enabled -> if(enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.action { vm.repo.updateProfile { it.copy(reminders = enabled) } } }
        Field("Час напоминания, 0–23", hour, { hour = it }, true)
        TextButton(onClick = { val h = hour.toIntOrNull(); if(h != null && h in 0..23) vm.action { vm.repo.updateProfile { it.copy(reminderHour = h) }; vm.notify("Время напоминания сохранено") } else vm.notify("Час: целое число от 0 до 23") }) { Text("Сохранить час напоминания") }
        Text("WorkManager: напоминание приблизительное, Android может задержать его в режиме экономии. По умолчанию выключено.", style = MaterialTheme.typography.bodySmall)
        Text("Километровые сигналы используют общие настройки звука и вибрации.", style = MaterialTheme.typography.bodySmall)
        SettingToggle("Сигнал каждого километра GPS", p.runSignals) { value -> vm.action { vm.repo.updateProfile { it.copy(runSignals = value) } } }
    }
}
@Composable private fun SettingToggle(text: String, checked: Boolean, change: (Boolean) -> Unit) { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(text, Modifier.weight(1f)); Switch(checked, change) } }
@Composable fun UserMap(vm: FitViewModel, points: List<GeoPoint>) { val profile by vm.profile.collectAsStateWithLifecycle(); if(profile.mapEnabled) MapPanel(points) else { Text("Карта отключена · локальная схема"); RouteFallback(points) } }
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as FitApp; app.ready.await(); val repo = app.repository; val p = repo.profile.first(); val today = LocalDate.now()
        if(p.reminders && LocalTime.now().hour >= p.reminderHour && (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            val marker = stringPreferencesKey("reminder-last-date")
            val last = applicationContext.settingsStore.data.first()[marker]
            val planned = repo.snapshot().workouts.count { !it.template && !it.recoveryDay && it.status == "planned" && it.date == today.toString() }
            if(ReminderPolicy.shouldNotify(p.reminders, p.reminderHour, LocalDateTime.now(), last, planned)) {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("plan-reminders", "Запланированные тренировки", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(applicationContext, 46, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(46, NotificationCompat.Builder(applicationContext, "plan-reminders").setSmallIcon(R.drawable.ic_logo).setContentTitle("План на сегодня").setContentText("Запланировано тренировок: $planned").setContentIntent(open).setAutoCancel(true).build())
                applicationContext.settingsStore.edit { it[marker] = today.toString() }
            }
        }
        Result.success()
    } catch(e: CancellationException) { throw e } catch(_: Exception) { Result.retry() }
}
