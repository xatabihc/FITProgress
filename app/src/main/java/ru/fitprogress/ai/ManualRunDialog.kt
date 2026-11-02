package ru.fitprogress.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.*

@Composable fun ManualRunDialog(dismiss: () -> Unit, save: (Run) -> Unit) {
    var distance by rememberSaveable { mutableStateOf("") }; var minutes by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Беговая дорожка") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("Данные будут помечены как введённые вручную. GPS-маршрут не создаётся.")
            Field("Дистанция, км", distance, { distance = it }, true); Field("Продолжительность, мин", minutes, { minutes = it }, true); Field("Дата, ГГГГ-ММ-ДД", date, { date = it })
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val day = LocalDate.parse(date); val now = System.currentTimeMillis()
            val end = if(day == LocalDate.now()) now else day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val duration = minutes.replace(',', '.').toDouble(); require(duration.isFinite() && duration > 0 && duration <= 10080)
            ManualRuns.create(distance.replace(',', '.').toDouble() * 1000, (duration * 60000).toLong(), end, now)
        }.onSuccess(save).onFailure { error = it.message ?: "Проверьте дату и значения" }
    }) { Text("Сохранить ручную запись") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
