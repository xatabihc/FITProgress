package ru.fitprogress.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ImportScreen(vm: FitViewModel) {
    val context = LocalContext.current
    val catalog by vm.exercises.collectAsStateWithLifecycle()
    val state by vm.workoutImport.collectAsStateWithLifecycle()
    val source = state.source; val preview = state.preview; val mapping = state.mapping
    var chooser by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val report = state.report
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.exclusive {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readLimitedUtf8(it, 20_000_000) } ?: error("Не удалось открыть файл") }
        val result = withContext(Dispatchers.Default) { WorkoutImport.parse(text, catalog) }
        vm.workoutImport.value = WorkoutImportState(source = text, preview = result)
    } }
    Page {
        item { Title("Импорт тренировок", "Пользовательский экспорт JSON · Рубеж12 и другие приложения") }
        item { Text("Выберите доступный файл экспорта. Поддерживаются списки workouts, trainings, trainingSessions или тренировки с датой, статусом, упражнениями и подходами. Формат закрытой базы другого приложения не читается. Если схема файла отличается, неподдерживаемые записи будут перечислены в отчёте.") }
        item { Action("Выбрать файл JSON") { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) } }
        if(report.isNotBlank()) item { Panel { Text(report) } }
        preview?.let { p ->
            item { Panel { Text("Предварительный просмотр", style = MaterialTheme.typography.titleLarge); Text("Готово: ${p.backup.workouts.size} · пропущено: ${p.skipped}"); Text("Завершено: ${p.backup.workouts.count { it.status == "done" }} · на паузе: ${p.backup.workouts.count { it.status == "paused" }} · запланировано: ${p.backup.workouts.count { it.status == "planned" }}"); Text("Отсутствующие числа: 0, без отметки выполнения подход не входит в статистику. Отсутствующие даты и статусы не придумываются. Незавершённые записи сохраняются как черновики с выполненными подходами. Повторный импорт того же ID пропускается.") } }
            items(p.matches) { match -> val title = match.substringBefore(" → "); TextButton(onClick = { chooser = title; query = title }) { Text(match) } }
            items(p.backup.workouts, key = { it.id }) { w -> Panel { Text("${w.date} · ${w.name}"); Text("${statusLabel(w)} · ${w.items.size} упр. · ${w.completedSets()} выполненных подходов") } }
            items(p.issues) { Text(it, color = MaterialTheme.colorScheme.error) }
            item { Action("Добавить проверенные тренировки") { vm.exclusive {
                val result = vm.repo.importWorkouts(p.backup)
                vm.workoutImport.value = WorkoutImportState(report = "Добавлено тренировок: ${result.first}. Совпадений в истории: ${result.second}. Пропущено при разборе: ${p.skipped}.")
            } } }
        }
    }
    chooser?.let { title -> AlertDialog(onDismissRequest = { chooser = null }, title = { Text("Сопоставить упражнение") }, text = {
        Column { Field("Поиск", query, { query = it }); androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(catalog.filter { it.name.contains(query, true) }.take(100), key = { it.id }) { e -> TextButton(onClick = {
                val next = mapping + (title to e.id)
                vm.exclusive { val result = withContext(Dispatchers.Default) { WorkoutImport.parse(requireNotNull(source), catalog, next) }; vm.workoutImport.value = state.copy(mapping = next, preview = result); chooser = null }
            }) { Text(e.name) } }
        } }
    }, confirmButton = { TextButton(onClick = { chooser = null }) { Text("Закрыть") } }) }
}
