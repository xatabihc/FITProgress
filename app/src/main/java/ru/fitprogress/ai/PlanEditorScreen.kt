package ru.fitprogress.ai

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable fun PlanEditorScreen(vm: FitViewModel, id: String) {
    val rows by vm.workouts.collectAsStateWithLifecycle(); val catalog by vm.exercises.collectAsStateWithLifecycle()
    val plan = rows.firstOrNull { it.id == id }?.plan
    if(plan == null) { Empty("Загрузка программы…"); return }
    var week by rememberSaveable(id) { mutableIntStateOf(0) }
    var header by remember { mutableStateOf(false) }; var dayId by rememberSaveable { mutableStateOf<String?>(null) }
    var newDay by rememberSaveable { mutableStateOf(false) }; var draftId by rememberSaveable { mutableStateOf(newId()) }; var apply by remember { mutableStateOf<TrainingPlan?>(null) }
    var copy by remember { mutableStateOf(false) }; var shift by remember { mutableStateOf(false) }; var deleteDay by remember { mutableStateOf<PlanDay?>(null) }
    week = week.coerceIn(0, plan.weeks - 1)
    Page {
        item { Title(plan.name, "${plan.weeks} недель · начало ${plan.startDate}") }
        item { Text(plan.notes) }
        item { Action("Название, длительность и начало", Icons.Default.Edit) { header = true } }
        item { Choice((1..plan.weeks).map { "Неделя $it" }, "Неделя ${week + 1}") { week = it.substringAfterLast(' ').toInt() - 1 } }
        item { Text("${LocalDate.parse(plan.startDate).plusDays(week * 7L)} — ${LocalDate.parse(plan.startDate).plusDays(week * 7L + 6)}") }
        items((0..6).toList(), key = { "day-$it" }) { weekday ->
            val offset = week * 7 + weekday; val sessions = plan.days.filter { it.offset == offset }
            Panel {
                Text("День ${weekday + 1} · ${LocalDate.parse(plan.startDate).plusDays(offset.toLong())}", style = MaterialTheme.typography.titleMedium)
                if(sessions.isEmpty()) Text("Отдых / свободный день")
                sessions.forEach { day ->
                    Text(day.name, style = MaterialTheme.typography.titleLarge)
                    Text(if(day.recovery) "Восстановление" else "${day.items.size} упражнений · ${day.items.sumOf { it.sets.size }} подходов")
                    if(day.block.isNotBlank()) Text("Блок: ${day.block}")
                    if(day.deload) Text("Разгрузка")
                    Row { TextButton(onClick = { dayId = day.id }) { Text("Изменить") }; TextButton(onClick = { deleteDay = day }) { Text("Удалить") } }
                }
            }
        }
        item { Action("Добавить занятие / восстановление") { draftId = newId(); newDay = true } }
        item { OutlinedButton(onClick = { copy = true }) { Text("Копировать выбранную неделю") } }
        item { OutlinedButton(onClick = { vm.updatePlan(id) { PlanBuilder.deload(it, week) } }) { Text("Сделать неделю разгрузочной: −30% подходов") } }
        item { OutlinedButton(onClick = { shift = true }) { Text("Сдвинуть расписание целиком") } }
        item { Action("Просмотреть и применить будущий план", Icons.Default.Preview) { apply = plan } }
        item { Text("Редактирование сохраняет определение программы. Календарь меняется только после подтверждения. Завершённые, текущие и прошлые занятия защищены.", style = MaterialTheme.typography.bodySmall) }
    }
    if(header) {
        var name by rememberSaveable { mutableStateOf(plan.name) }; var weeks by rememberSaveable { mutableStateOf(plan.weeks.toString()) }; var start by rememberSaveable { mutableStateOf(plan.startDate) }; var notes by rememberSaveable { mutableStateOf(plan.notes) }; var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { header = false }, title = { Text("Параметры программы") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Field("Название", name, { name = it }); Field("Недели: 1–52", weeks, { weeks = it }, true); Field("Начало, ГГГГ-ММ-ДД", start, { start = it }); Field("Заметки", notes, { notes = it }, lines = 2); Text(error) } }, confirmButton = { TextButton(onClick = {
            runCatching { plan.copy(name = name.trim(), weeks = weeks.toInt(), startDate = start, notes = notes).also(PlanBuilder::validate) }.onSuccess { updated -> vm.updatePlan(id) { updated }; header = false }.onFailure { error = "Проверьте дату и 1–52 недели. При сокращении сначала перенесите или удалите выходящие за срок занятия." }
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { header = false }) { Text("Отмена") } })
    }
    val existing = plan.days.firstOrNull { it.id == dayId }
    if(existing != null || newDay) PlanDayDialog(existing ?: PlanDay(id = draftId, offset = week * 7), plan.weeks, catalog, { dayId = null; newDay = false }) { updated ->
        vm.updatePlan(id) { p -> p.copy(days = p.days.filterNot { it.id == updated.id } + updated) }; dayId = null; newDay = false
    }
    if(deleteDay != null) Confirm("Удалить день из программы?", "Календарь не изменится до применения плана. Выполненная история сохранится.", { deleteDay = null }) { val target = deleteDay!!.id; vm.updatePlan(id) { it.copy(days = it.days.filterNot { d -> d.id == target }) }; deleteDay = null }
    if(copy || shift) {
        var value by remember { mutableStateOf(if(copy) (week + 2).coerceAtMost(plan.weeks).toString() else "7") }; var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { copy = false; shift = false }, title = { Text(if(copy) "Копировать неделю ${week + 1}" else "Сдвиг программы") }, text = { Column { Field(if(copy) "Целевая неделя (её план будет заменён)" else "Сдвиг в днях (можно отрицательный)", value, { value = it }, true); Text("Изменяется только определение программы. Перед применением календаря будет предварительный просмотр."); Text(error) } }, confirmButton = { TextButton(onClick = {
            runCatching { if(copy) PlanBuilder.copyWeek(plan, week, value.toInt() - 1) else PlanBuilder.shift(plan, value.toLong().also { require(it in -3650L..3650L) }) }.onSuccess { updated -> vm.updatePlan(id) { updated }; copy = false; shift = false }.onFailure { error = "Введите корректную неделю или сдвиг; исходная и целевая недели должны отличаться." }
        }) { Text(if(copy) "Подтвердить замену недели" else "Сдвинуть") } }, dismissButton = { TextButton(onClick = { copy = false; shift = false }) { Text("Отмена") } })
    }
    if(apply != null) {
        val expected = apply!!; val diff = remember(expected, rows) { PlanBuilder.preview(expected, rows, LocalDate.now()) }
        AlertDialog(onDismissRequest = { apply = null }, title = { Text("Изменения календаря") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("Добавится: ${diff.inserts.size}; обновится: ${diff.updates.size}; удалится будущих плановых: ${diff.deleteIds.size}.")
            (diff.inserts + diff.updates).forEach { Text("${it.date} · ${it.name} · ${it.items.sumOf { i -> i.sets.size }} подходов") }
            Text("Прошлые, выполняемые и завершённые занятия не меняются.")
        } }, confirmButton = { TextButton(onClick = { vm.exclusive { vm.repo.applyPlan(id, expected, LocalDate.now()); apply = null; vm.notify("Будущий план обновлён") } }) { Text("Подтвердить изменения") } }, dismissButton = { TextButton(onClick = { apply = null }) { Text("Отмена") } })
    }
}

@Composable private fun PlanDayDialog(day: PlanDay, weeks: Int, catalog: List<Exercise>, dismiss: () -> Unit, save: (PlanDay) -> Unit) {
    val saver = remember { Saver<PlanDay, String>(save = { BackupCodec.gson.toJson(it) }, restore = { BackupCodec.gson.fromJson(it, PlanDay::class.java) }) }
    var draft by rememberSaveable(day.id, stateSaver = saver) { mutableStateOf(day) }
    var dayNumber by rememberSaveable(day.id) { mutableStateOf((day.offset + 1).toString()) }
    var chooser by remember { mutableStateOf(false) }; var selectedItem by remember { mutableStateOf<String?>(null) }; var selectedSet by remember { mutableStateOf<SetEntry?>(null) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("День программы") }, text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Название", draft.name, { draft = draft.copy(name = it) }); Field("День от начала (1–${weeks * 7})", dayNumber, { dayNumber = it }, true)
        Row { Checkbox(draft.recovery, { if(!it || draft.items.isEmpty()) draft = draft.copy(recovery = it) else error = "Сначала удалите упражнения для дня отдыха" }); Text("День восстановления") }
        Field("Тренировочный блок", draft.block, { draft = draft.copy(block = it) }); Field("Заметки", draft.notes, { draft = draft.copy(notes = it) }, lines = 2)
        draft.items.forEach { item ->
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            item.sets.forEachIndexed { index, s -> TextButton(onClick = { selectedItem = item.id; selectedSet = s }) { Text("${index + 1}: ${s.reps} × ${s.weight} кг · ${s.seconds} сек") } }
            Row { TextButton(onClick = { draft = draft.copy(items = draft.items.map { if(it.id == item.id && it.sets.size < 100) it.copy(sets = it.sets + it.sets.last().copy(id = newId())) else it }) }) { Text("+ Подход") }; TextButton(onClick = { draft = draft.copy(items = draft.items.filterNot { it.id == item.id }) }) { Text("Убрать") } }
        }
        if(!draft.recovery) OutlinedButton(onClick = { chooser = true }) { Text("Добавить упражнение") }
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = { runCatching { draft.copy(offset = dayNumber.toInt() - 1).also { PlanBuilder.validate(TrainingPlan(weeks = weeks, days = listOf(it))) } }.onSuccess(save).onFailure { error = "Проверьте название, номер дня и параметры" } }) { Text("Сохранить день") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
    if(chooser) {
        var search by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Упражнения") },
            text = {
                Column {
                    Field("Поиск", search, { search = it })
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        catalog.filter { it.name.contains(search, true) }.forEach { e ->
                            TextButton(onClick = { draft = draft.copy(items = draft.items + Training.item(e)); chooser = false }) { Text(e.name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooser = false }) { Text("Закрыть") } }
        )
    }
    if(selectedSet != null) SetDialog(selectedSet!!, false, { selectedSet = null }) { result -> draft = draft.copy(items = draft.items.map { i -> if(i.id == selectedItem) i.copy(sets = i.sets.map { if(it.id == result.id) result else it }) else i }); selectedSet = null }
}
