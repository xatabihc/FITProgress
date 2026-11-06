package ru.fitprogress.ai

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private fun goalUnit(kind: String) = when(kind) { "distance" -> "км"; "pace" -> "мин/км (5,5 = 5:30)"; "strength" -> "кг"; "program" -> "%"; "consistency" -> "недель"; else -> "занятий" }
@Composable fun GoalsScreen(vm: FitViewModel) {
    val personal by vm.personalData.collectAsStateWithLifecycle(); val goals = personal.goals; val workouts by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }; var create by rememberSaveable { mutableStateOf(false) }; var archive by rememberSaveable { mutableStateOf(false) }
    Page {
        item { Title("Спортивные цели", "Прогресс рассчитывается из завершённой истории") }
        item { Action("Добавить цель", enabled = personal.loaded) { create = true } }
        item { Choice(listOf("Активные", "Архив"), if(archive) "Архив" else "Активные") { archive = it == "Архив" } }
        if(goals.none { it.archived == archive }) item { Empty("Целей пока нет. Выберите измеримый результат и срок.") }
        items(goals.filter { it.archived == archive }, key = { it.id }) { goal ->
            val progress = GoalTracker.calculate(goal, workouts, runs, LocalDate.now())
            Panel {
                Text(goal.title, style = MaterialTheme.typography.titleLarge); Text("${goal.from} — ${goal.until} · ${goalUnit(goal.kind)}")
                Metric(if(progress.achieved) "Достигнуто" else "Текущий результат", "%.2f / %.2f".format(progress.current, progress.target))
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth()); Text(progress.explanation)
                progress.history.takeLast(7).forEach { Text("${it.first} · %.2f".format(it.second), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { editing = goal.id }) { Text("Изменить цель") }
                TextButton(onClick = { vm.action { vm.repo.saveGoal(goal.copy(archived = !goal.archived)) } }) { Text(if(goal.archived) "Вернуть из архива" else "Архивировать") }
            }
        }
    }
    if(personal.loaded && (create || editing != null && goals.any { it.id == editing })) GoalDialog(goals.find { it.id == editing } ?: SportGoal(), vm, { create = false; editing = null }) { goal -> vm.exclusive { vm.repo.saveGoal(goal); create = false; editing = null } }
}
@Composable private fun GoalDialog(initial: SportGoal, vm: FitViewModel, dismiss: () -> Unit, save: (SportGoal) -> Unit) {
    val exercises by vm.exercises.collectAsStateWithLifecycle(); val workouts by vm.workouts.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf(initial.title) }; var kind by rememberSaveable { mutableStateOf(initial.kind) }; var target by rememberSaveable { mutableStateOf(initial.target.toString()) }
    var from by rememberSaveable { mutableStateOf(initial.from) }; var until by rememberSaveable { mutableStateOf(initial.until) }; var source by rememberSaveable { mutableStateOf(initial.source) }
    var exercise by rememberSaveable { mutableStateOf(initial.exerciseId) }; var program by rememberSaveable { mutableStateOf(initial.programId) }; var frequency by rememberSaveable { mutableStateOf(initial.weeklySessions.toString()) }; var reps by rememberSaveable { mutableStateOf(initial.minReps.toString()) }; var notes by rememberSaveable { mutableStateOf(initial.notes) }; var error by remember { mutableStateOf("") }
    val programs = (Catalog.programs.associate { it.id to it.name } + workouts.filter { it.plan != null }.associate { it.id to it.name }).filterKeys { id -> workouts.any { it.program == id || it.id == id && it.plan != null } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Персональная цель") }, text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Название", title, { title = it }); Choice(goalKinds.values.toList(), goalKinds.getValue(kind)) { label -> kind = goalKinds.entries.first { it.value == label }.key }
        if(kind != "program") Field("Цель, ${goalUnit(kind)}", target, { target = it }, true) else Text("Цель — 100% завершённых занятий программы")
        Field("С, ГГГГ-ММ-ДД", from, { from = it }); Field("По, ГГГГ-ММ-ДД", until, { until = it })
        if(kind in listOf("distance", "pace", "sessions", "consistency")) Choice(listOf("Все записи", "Только GPS", "Только вручную"), when(source) { "gps" -> "Только GPS"; "manual" -> "Только вручную"; else -> "Все записи" }) { source = when(it) { "Только GPS" -> "gps"; "Только вручную" -> "manual"; else -> "all" } }
        if(kind == "strength") {
            var query by rememberSaveable { mutableStateOf("") }
            Text("Выберите упражнение"); Field("Поиск упражнения", query, { query = it })
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(220.dp)) {
                items(exercises.filter { it.name.contains(query, true) }, key = { it.id }) { e ->
                    FilterChip(selected = exercise == e.id, onClick = { exercise = e.id }, label = { Text(e.name) })
                }
            }
            Field("Минимум повторений в подходе", reps, { reps = it }, true)
        }
        if(kind == "program") { if(programs.isEmpty()) Text("Сначала создайте или начните программу"); programs.forEach { (id, name) -> FilterChip(selected = program == id, onClick = { program = id }, label = { Text(name) }) } }
        if(kind == "consistency") Field("Минимум занятий за календарную неделю", frequency, { frequency = it }, true)
        Field("Заметки", notes, { notes = it }, lines = 2); if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        runCatching { initial.copy(title = title.trim(), kind = kind, target = if(kind == "program") 100.0 else target.replace(',', '.').toDouble(), from = from, until = until, source = source, exerciseId = exercise, programId = program, weeklySessions = frequency.toInt(), minReps = reps.toInt(), notes = notes).also(PersonalValidation::goal) }.onSuccess(save).onFailure { error = "Проверьте название, даты, цель и выбранное упражнение/программу" }
    }) { Text("Сохранить цель") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun WellbeingScreen(vm: FitViewModel) {
    val personal by vm.personalData.collectAsStateWithLifecycle(); val entries = personal.wellbeing; var selected by rememberSaveable { mutableStateOf<String?>(null) }; var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        item { Title("Дневник самочувствия", "Необязательные оценки для осторожного планирования") }
        item { Action("Заполнить дневник", enabled = personal.loaded) { selected = LocalDate.now().toString() } }
        if(entries.isEmpty()) item { Empty("Записей нет. Дневник не требуется для начала тренировки.") }
        items(entries, key = { it.date }) { entry -> Panel {
            Text(entry.date, style = MaterialTheme.typography.titleLarge); Text("Энергия ${entry.energy}/10 · усталость ${entry.fatigue}/10"); Text("Сон ${entry.sleep}/10 · болезненность ${entry.soreness}/10 · восстановление ${entry.recovery}/10")
            if(entry.pain) Text("Отмечена боль", color = MaterialTheme.colorScheme.error); if(entry.comment.isNotBlank()) Text(entry.comment)
            TextButton(onClick = { selected = entry.date }) { Text("Изменить запись") }; TextButton(onClick = { deleting = entry.date }) { Text("Удалить запись") }
        } }
    }
    if(selected != null && personal.loaded) {
        val initial = entries.find { it.date == selected } ?: Wellbeing(date = selected!!)
        WellbeingDialog(initial, { selected = null }) { vm.exclusive { vm.repo.saveWellbeing(it); selected = null } }
    }
    if(deleting != null) Confirm("Удалить запись дневника?", "Тренировочная история сохранится.", { deleting = null }) { val date = deleting!!; vm.exclusive { vm.repo.deleteWellbeing(date); deleting = null } }
}
@Composable private fun WellbeingDialog(initial: Wellbeing, dismiss: () -> Unit, save: (Wellbeing) -> Unit) {
    var date by rememberSaveable { mutableStateOf(initial.date) }; var energy by rememberSaveable { mutableFloatStateOf(initial.energy.toFloat()) }; var fatigue by rememberSaveable { mutableFloatStateOf(initial.fatigue.toFloat()) }; var sleep by rememberSaveable { mutableFloatStateOf(initial.sleep.toFloat()) }; var soreness by rememberSaveable { mutableFloatStateOf(initial.soreness.toFloat()) }; var recovery by rememberSaveable { mutableFloatStateOf(initial.recovery.toFloat()) }; var pain by rememberSaveable { mutableStateOf(initial.pain) }; var comment by rememberSaveable { mutableStateOf(initial.comment) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Самочувствие за день") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Field("Дата, ГГГГ-ММ-ДД", date, { date = it }); Text("При выборе другой даты сохраняется отдельная запись; старая не удаляется. Если выбранная дата уже существует, её оценка обновится.", style = MaterialTheme.typography.bodySmall)
        Rating("Энергия", energy, { energy = it }, 1); Rating("Усталость", fatigue, { fatigue = it }, 0); Rating("Качество сна", sleep, { sleep = it }, 1); Rating("Мышечная болезненность", soreness, { soreness = it }, 0); Rating("Восстановление", recovery, { recovery = it }, 1)
        Row { Checkbox(pain, { pain = it }); Text("Боль (ограничивает прогрессию)", Modifier.weight(1f)) }; Field("Комментарий", comment, { comment = it }, lines = 2); Text(error)
    } }, confirmButton = { TextButton(onClick = { runCatching { Wellbeing(date, energy.roundToInt(), fatigue.roundToInt(), sleep.roundToInt(), soreness.roundToInt(), recovery.roundToInt(), pain, comment).also { PersonalValidation.wellbeing(it); require(LocalDate.parse(it.date) <= LocalDate.now()) } }.onSuccess(save).onFailure { error = "Проверьте дату и оценки; будущая дата недоступна" } }) { Text("Сохранить дневник") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable private fun Rating(label: String, value: Float, change: (Float) -> Unit, min: Int) { Text("$label: ${value.roundToInt()}/10"); Slider(value = value, onValueChange = change, valueRange = min.toFloat()..10f, steps = 9 - min) }
@Composable fun CoachScreen(vm: FitViewModel, manual: () -> Unit) {
    val workouts by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle(); val diary by vm.wellbeing.collectAsStateWithLifecycle()
    val engine = remember { AdaptiveCoach() }; val today = LocalDate.now(); var draft by remember { mutableStateOf<Pair<CoachPreview, Backup>?>(null) }
    val review = remember(workouts, runs, diary, today) { engine.review(workouts, runs, diary, today) }
    Page {
        item { Title("Adaptive Coach", "Анализ недели с ${weekOf(today).minusWeeks(1)}; план на следующую неделю") }
        item {
            Panel {
                Text("Прошлая неделя · ${review.from} — ${review.until}", style = MaterialTheme.typography.titleLarge)
                Text("Запланировано занятий: ${review.plannedSessions}; отмечено выполненными: ${review.completedPlannedSessions}.")
                Text("Фактически завершено: ${review.completedSessions} тренировок и ${review.completedRuns} пробежек.")
                Text("Пробег: %.2f км · активных дней: ${review.activeDays}".format(review.runningKm))
                Text("Записей самочувствия: ${review.diaryEntries}")
                if(review.painReported) Text("Отмечена боль. Автоматическое увеличение нагрузки ограничено.", color = MaterialTheme.colorScheme.error)
                Text("Отчёт основан только на сохранённых результатах. Рекомендации можно просмотреть и отклонить.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(engine.options(workouts, runs, diary, today), key = { it.id }) { option -> Panel {
            Text(option.title, style = MaterialTheme.typography.titleLarge); Text(option.metrics); Text(option.reason)
            Action(if(option.id == "manual") "Открыть календарь" else "Просмотреть разницу", Icons.Default.Preview, option.enabled) {
                if(option.id == "manual") manual() else vm.exclusive { val snapshot = vm.repo.snapshot(); val current = engine.options(snapshot.workouts, snapshot.runs, snapshot.wellbeing, today).first { it.id == option.id }; draft = engine.preview(current, snapshot.workouts, today) to snapshot }
            }
        } }
    }
    if(draft != null) {
        val (preview, snapshot) = draft!!
        val replacing = snapshot.workouts.count { !it.template && it.status == "planned" && it.date >= preview.from && it.date <= preview.until }
        AlertDialog(onDismissRequest = { draft = null }, title = { Text("${preview.option.title}: ${preview.from}") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Будет заменено плановых занятий целевой недели: $replacing. Новый план: ${preview.workouts.size} занятий. История не меняется.")
            preview.differences.forEach { Text(it) }; Text("Одна рекомендация на неделю применяется один раз; затем доступно ручное редактирование.")
        } }, confirmButton = { TextButton(enabled = preview.workouts.isNotEmpty(), onClick = { vm.exclusive { vm.repo.applyCoach(preview, snapshot); draft = null; vm.notify("План подтверждён и сохранён") } }) { Text("Подтвердить план Coach") } }, dismissButton = { TextButton(onClick = { draft = null }) { Text("Отклонить") } })
    }
}
@Composable fun DetailedAnalyticsScreen(vm: FitViewModel) {
    val workouts by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle(); val diary by vm.wellbeing.collectAsStateWithLifecycle(); val catalog by vm.exercises.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf("30") }; var source by rememberSaveable { mutableStateOf("Все") }
    val until = LocalDate.now(); val days = period.toInt(); val from = until.minusDays(days - 1L)
    val selectedRuns = runs.filter { source == "Все" || it.recordingMode == if(source == "GPS") "gps" else "manual" }
    val current = remember(workouts, selectedRuns, diary, catalog, from) { Analytics.summarize(workouts, selectedRuns, diary, from, until, catalog) }
    val previous = remember(workouts, selectedRuns, diary, catalog, from) { Analytics.summarize(workouts, selectedRuns, diary, from.minusDays(days.toLong()), from.minusDays(1), catalog) }
    Page {
        item { Title("Аналитика и сравнение", "$from — $until; сравнение с предыдущими $days днями") }
        item { Choice(listOf("7", "30", "90", "365"), period) { period = it } }; item { Choice(listOf("Все", "GPS", "Вручную"), source) { source = it } }
        item { Panel {
            Metric("Занятия: текущий / предыдущий", "${current.workouts + current.runs} / ${previous.workouts + previous.runs}")
            Text("Время: ${formatTime(current.durationMs)} / ${formatTime(previous.durationMs)}"); Text("Объём: %.0f / %.0f кг".format(current.volume, previous.volume)); Text("GPS: %.2f / %.2f км".format(current.gpsKm, previous.gpsKm)); Text("Ручные: %.2f / %.2f км".format(current.manualKm, previous.manualKm)); Text("Темп: ${formatPace(current.pace)} / ${formatPace(previous.pace)}"); Text("Дней активности: ${current.activeDays} / ${previous.activeDays}")
        } }
        val dates = (0 until days).map { from.plusDays(it.toLong()) }
        item { Panel { InteractiveSeries(dates.map { it.toString() to workouts.filter { w -> w.status == "done" && w.completedDate() == it }.sumOf { w -> w.volume() } }, "Силовой объём по дням, кг") } }
        item { Panel { InteractiveSeries(dates.map { day -> day.toString() to selectedRuns.filter { !it.demo && it.status == "done" && it.localDate() == day }.sumOf { it.distance } / 1000 }, "Бег по дням, км") } }
        item { Panel { Text("Объём по основной группе", style = MaterialTheme.typography.titleLarge); current.muscleVolume.forEach { (group, value) -> Text("$group: %.0f кг".format(value)) }; Text("Каждый подход учитывается один раз по сохранённой основной категории. Для старых записей используется каталог, отсутствующая группа помечается отдельно.", style = MaterialTheme.typography.bodySmall) } }
        items(current.exercises, key = { it.id }) { e -> Panel { Text(e.name, style = MaterialTheme.typography.titleLarge); Text("Объём %.0f кг · ${e.repetitions} повторений · максимум ${e.maxWeight} кг".format(e.volume)); Text("Оценка e1RM (Эпли, 1–10 повт.): ${e.estimated1Rm?.let { "%.1f кг".format(it) } ?: "нет данных"}"); Text("e1RM — расчётная оценка, не выполненный максимальный вес.", style = MaterialTheme.typography.bodySmall) } }
        item { Panel { Text("Самочувствие за период"); Text("Средняя усталость: ${current.fatigue?.let { "%.1f/10".format(it) } ?: "нет записей"}"); Text("Среднее восстановление: ${current.recovery?.let { "%.1f/10".format(it) } ?: "нет записей"}") } }
    }
}
@Composable private fun InteractiveSeries(points: List<Pair<String, Double>>, label: String) {
    var index by rememberSaveable(label) { mutableIntStateOf(0) }; val selected = index.coerceIn(0, points.lastIndex.coerceAtLeast(0))
    LineChart(points.map { it.second }, label)
    if(points.isNotEmpty()) { Text("${points[selected].first}: %.2f".format(points[selected].second)); if(points.size > 1) Slider(selected.toFloat(), { index = it.roundToInt() }, valueRange = 0f..points.lastIndex.toFloat(), steps = (points.size - 2).coerceAtMost(364)) }
}
