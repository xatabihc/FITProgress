package ru.fitprogress.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { FitProgress() } }
}
private val Accent = Color(0xFFB4F667)
@Composable fun FitProgress(vm: FitViewModel = viewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val accent = when(profile.accent) { "cyan" -> Color(0xFF68D8F6); "orange" -> Color(0xFFFFB45B); else -> Accent }
    val dark = darkColorScheme(primary = accent, onPrimary = Color(0xFF162309), background = Color(0xFF101410), surface = Color(0xFF191F19), surfaceVariant = Color(0xFF283028))
    val light = lightColorScheme(primary = when(profile.accent) { "cyan" -> Color(0xFF00687B); "orange" -> Color(0xFF8B5000); else -> Color(0xFF3C6A12) }, background = Color(0xFFF5F8F1), surface = Color.White)
    MaterialTheme(colorScheme = if(profile.light) light else dark, typography = Typography()) {
        val nav = rememberNavController(); val back by nav.currentBackStackEntryAsState(); val route = back?.destination?.route ?: "home"
        val sessions by vm.workouts.collectAsStateWithLifecycle()
        val activeId = sessions.firstOrNull { it.status in listOf("active", "paused") }?.id
        val context = LocalContext.current
        LaunchedEffect(activeId) {
            if(activeId != null) {
                try { (context.applicationContext as FitApp).ready.await() }
                catch(error: kotlinx.coroutines.CancellationException) { throw error }
                catch(error: Exception) { vm.loadFailure.value = "Не удалось восстановить локальное состояние. История не удалялась. Перезапустите приложение."; return@LaunchedEffect }
                try { WorkoutTimerService.start(context) }
                catch(error: kotlinx.coroutines.CancellationException) { throw error }
                catch(error: Exception) { vm.notify("Фоновый таймер не запущен: ${error.message}") }
            }
        }
        val loadFailure by vm.loadFailure.collectAsStateWithLifecycle()
        if(loadFailure != null) AlertDialog(onDismissRequest = {}, title = { Text("Ошибка чтения данных") }, text = { Text(loadFailure!!) }, confirmButton = { TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text("Закрыть") } })
        val snackbar = remember { SnackbarHostState() }; val busy by vm.busy.collectAsStateWithLifecycle()
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(Unit) { WorkoutTimerService.failure.collect { if(it != null) snackbar.showSnackbar(it) } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if(route !in listOf("home", "catalog", "calendar", "running", "stats")) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Column(Modifier.weight(1f)) { Text("FITPROGRESS", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp); Text("FitProgress · 2.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { if(route != "settings") nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Настройки") }
            }
        }, bottomBar = {
            NavigationBar {
                listOf(Triple("home", "Главная", Icons.Default.Home), Triple("catalog", "Каталог", Icons.Default.FitnessCenter), Triple("calendar", "План", Icons.Default.CalendarMonth), Triple("running", "Бег", Icons.Default.DirectionsRun), Triple("stats", "Прогресс", Icons.Default.Insights)).forEach { (path, name, icon) ->
                    NavigationBarItem(selected = route == path, onClick = { nav.navigate(path) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, name) }, label = if(androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f) null else { { Text(name, fontSize = 10.sp) } })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(vm, { nav.navigate("workout/$it") }, { nav.navigate(it) }) }
                    composable("catalog") { CatalogScreen(vm, { nav.navigate("exercise/$it") }) }
                    composable("exercise/{id}") { ExerciseScreen(vm, it.arguments?.getString("id") ?: "new", { nav.popBackStack() }) }
                    composable("workout/{id}") { WorkoutScreen(vm, it.arguments?.getString("id") ?: "", { nav.popBackStack() }) }
                    composable("calendar") { CalendarScreen(vm, { nav.navigate("workout/$it") }) }
                    composable("programs") { ProgramsScreen(vm, { nav.navigate("calendar") }, { nav.navigate("plan/$it") }) }
                    composable("plan/{id}") { PlanEditorScreen(vm, it.arguments?.getString("id") ?: "") }
                    composable("running") { RunningScreen(vm, { nav.navigate("run/$it") }) }
                    composable("run/{id}") { RunDetail(vm, it.arguments?.getString("id") ?: "") }
                    composable("stats") { StatsScreen(vm, { nav.navigate("analysis") }) }
                    composable("settings") { SettingsScreen(vm) }
                    composable("recommendations") { CoachScreen(vm, { nav.navigate("calendar") }) }
                    composable("goals") { GoalsScreen(vm) }
                    composable("wellbeing") { WellbeingScreen(vm) }
                    composable("analysis") { DetailedAnalyticsScreen(vm) }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}
@Composable fun Page(content: LazyListScope.() -> Unit) { LazyColumn(Modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
@Composable fun Title(text: String, subtitle: String = "") { Column { Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); if(subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } }
@Composable fun Panel(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) } }
@Composable fun Action(text: String, icon: ImageVector = Icons.Default.Add, enabled: Boolean = true, click: () -> Unit) { Button(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(text) } }
@Composable fun Metric(label: String, value: String) { Column(Modifier.padding(vertical = 4.dp)) { Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium) } }
@Composable fun Empty(text: String) { Panel { Icon(Icons.Default.SportsGymnastics, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)); Text(text) } }
@Composable fun Field(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, lines: Int = 1) { OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = lines == 1, minLines = lines, keyboardOptions = KeyboardOptions(keyboardType = if(numeric) KeyboardType.Decimal else KeyboardType.Text)) }
@Composable fun Choice(values: List<String>, selected: String, onSelect: (String) -> Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(it) }) } } }
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, accept: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = accept) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }) }
@Composable fun clockNow(): Long { var now by remember { mutableLongStateOf(System.currentTimeMillis()) }; LaunchedEffect(Unit) { while(true) { now = System.currentTimeMillis(); delay(1000) } }; return now }
fun monday(date: LocalDate = LocalDate.now()): LocalDate = date.with(DayOfWeek.MONDAY)
fun statusLabel(w: Workout) = if(w.plan != null) "Программа" else if(w.recoveryDay) "Восстановление" else if(w.template) "Шаблон" else when(w.status) { "done" -> "Завершено"; "active" -> "Выполняется"; "paused" -> "На паузе"; else -> "В плане" }
@Composable fun WorkoutCard(w: Workout, open: () -> Unit) { Card(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if(w.status == "done") Icons.Default.CheckCircle else Icons.Default.FitnessCenter, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(w.name, fontWeight = FontWeight.Bold); Text("${w.date} · ${statusLabel(w)}", style = MaterialTheme.typography.labelMedium); Text(w.plan?.let { "${it.weeks} недель · ${it.days.size} занятий" } ?: "${w.items.size} упр. · ${w.items.sumOf { it.sets.size }} подходов", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } }
@Composable fun HomeScreen(vm: FitViewModel, open: (String) -> Unit, navigate: (String) -> Unit) {
    val p by vm.profile.collectAsStateWithLifecycle(); val all by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    val today = LocalDate.now(); val start = monday(); val week = all.filter { !it.template && !it.recoveryDay && LocalDate.parse(it.date) >= start && LocalDate.parse(it.date) < start.plusDays(7) }; val done = week.filter { it.status == "done" }; val activity = all.filter { it.status == "done" && it.completedDate() >= start && it.completedDate() < start.plusDays(7) }
    val weekRuns = runs.filter { !it.demo && it.status == "done" && Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault()).toLocalDate().let { d -> d >= start && d < start.plusDays(7) } }
    val active = all.firstOrNull { it.status in listOf("active", "paused") }; val next = active ?: all.firstOrNull { !it.template && it.status == "planned" && !it.recoveryDay && LocalDate.parse(it.date) >= today }
    Page {
        item { Title("Привет, ${p.name}", today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")))) }
        item { Panel { Text("ТВОЯ НЕДЕЛЯ", letterSpacing = 2.sp, style = MaterialTheme.typography.labelMedium); Column { Metric("занятий", (activity.size + weekRuns.size).toString()); Metric("время", "${(activity.sumOf { it.accumulatedMs } + weekRuns.sumOf { it.accumulatedMs }) / 60000} мин") }; Metric("дистанция бега", "%.2f км".format(weekRuns.sumOf { it.distance } / 1000)); LinearProgressIndicator(progress = { if(week.isEmpty()) 0f else done.size.toFloat() / week.size }, modifier = Modifier.fillMaxWidth()); Text("План: ${done.size} / ${week.size} тренировок") } }
        item { Text(if(active != null) "Продолжить тренировку" else "Следующая тренировка", style = MaterialTheme.typography.titleLarge) }
        item { if(next != null) WorkoutCard(next) { open(next.id) } else Empty("Пока нет запланированных занятий. Создайте первую тренировку.") }
        item { Action("Создать тренировку") { vm.create(open) } }
        item { Action("Персональные цели", Icons.Default.Flag) { navigate("goals") } }
        item { Action("Дневник самочувствия", Icons.Default.Favorite) { navigate("wellbeing") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { navigate("programs") }, modifier = Modifier.weight(1f)) { Text("Программы") }; OutlinedButton(onClick = { navigate("catalog") }, modifier = Modifier.weight(1f)) { Text("Упражнения") } } }
        val programs = all.filter { it.program.isNotBlank() && it.status != "done" && !it.template }.groupBy { it.program }
        if(programs.isNotEmpty()) item { Panel { Text("Активные программы", style = MaterialTheme.typography.titleLarge); programs.forEach { (id, list) -> Text("${Catalog.programTitle(id)}: ${list.size} занятий впереди") } } }
        val recent = all.filter { it.status == "done" }.maxByOrNull { it.ended }
        if(recent != null) item { Panel { Text("Последний результат", style = MaterialTheme.typography.titleLarge); Text("${recent.name}: ${recent.completedSets()} подходов · ${"%.0f".format(recent.volume())} кг объёма"); TextButton(onClick = { open(recent.id) }) { Text("Открыть историю") } } }
        item { Action("План следующей недели", Icons.Default.AutoGraph) { navigate("recommendations") } }
    }
}
@Composable fun CatalogScreen(vm: FitViewModel, edit: (String) -> Unit) {
    val exercises by vm.exercises.collectAsStateWithLifecycle(); var query by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("Все") }
    var equipment by rememberSaveable { mutableStateOf("Любое") }; var level by rememberSaveable { mutableStateOf("Любой") }
    val search = remember(exercises) { ExerciseSearchIndex(exercises) }
    val equipmentOptions = remember(exercises) { listOf("Любое") + exercises.map { it.equipment }.distinct().sorted() }
    val levelOptions = listOf("Любой", "Начинающий", "Средний", "Продвинутый", "Не указан источником")
    val matches = remember(search, query, category, equipment, level) { search.filter(query, category, equipment, level) }
    Page {
        item { Title("Каталог упражнений", "${search.size} записей · поиск на устройстве · офлайн") }
        item { Field("Поиск упражнения", query, { query = it }) }
        item { Choice(listOf("Все") + categories, category) { category = it } }
        item { Choice(equipmentOptions, equipment) { equipment = it } }
        item { Choice(levelOptions, level) { level = it } }
        item { Text("Найдено: ${matches.size}", style = MaterialTheme.typography.labelMedium) }
        item { Action("Создать упражнение") { edit("new") } }
        if(matches.isEmpty()) item { Empty("Ничего не найдено. Измените фильтры или создайте своё упражнение.") }
        items(matches, key = { it.id }) { e ->
            Card(onClick = { edit(e.id) }, shape = RoundedCornerShape(22.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { ExerciseArt(e, Modifier.size(76.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(e.name, fontWeight = FontWeight.Bold); Text(e.category, color = MaterialTheme.colorScheme.primary); Text("${e.sets} × ${if(e.seconds > 0) "${e.seconds} сек" else "${e.reps} повт"} · ${e.equipment}", style = MaterialTheme.typography.bodySmall); OpenExerciseLibrary.sourceInfo(e.id)?.let { Text("${it.level} · ${if(it.type.isBlank()) "упражнение" else it.type}", style = MaterialTheme.typography.labelSmall) } } } }
        }
    }
}
@Composable fun ExerciseScreen(vm: FitViewModel, id: String, back: () -> Unit) {
    val all by vm.exercises.collectAsStateWithLifecycle(); val history by vm.workouts.collectAsStateWithLifecycle(); val existing = all.find { it.id == id }
    if(id != "new" && existing == null) { Empty("Загрузка упражнения…"); return }
    val initial = existing ?: Exercise()
    var name by rememberSaveable(id) { mutableStateOf(initial.name) }; var category by rememberSaveable(id) { mutableStateOf(initial.category) }
    var technique by rememberSaveable(id) { mutableStateOf(initial.technique) }; var muscles by rememberSaveable(id) { mutableStateOf(initial.muscles) }
    var equipment by rememberSaveable(id) { mutableStateOf(initial.equipment) }; var image by rememberSaveable(id) { mutableStateOf(initial.imageUri) }
    var sets by rememberSaveable(id) { mutableStateOf(initial.sets.toString()) }; var reps by rememberSaveable(id) { mutableStateOf(initial.reps.toString()) }; var weight by rememberSaveable(id) { mutableStateOf(initial.weight.toString()) }
    var seconds by rememberSaveable(id) { mutableStateOf(initial.seconds.toString()) }; var rest by rememberSaveable(id) { mutableStateOf(initial.rest.toString()) }; var notes by rememberSaveable(id) { mutableStateOf(initial.notes) }
    var error by remember { mutableStateOf("") }; var delete by remember { mutableStateOf(false) }; val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; image = uri.toString() } }
    fun build(copy: Boolean): Exercise = initial.copy(id = if(initial.builtIn || copy) newId() else initial.id, name = name.trim(), category = category, technique = technique, muscles = muscles, equipment = equipment, imageUri = image, sets = sets.toIntOrNull() ?: -1, reps = reps.toIntOrNull() ?: -1, weight = weight.replace(',', '.').toDoubleOrNull() ?: -1.0, seconds = seconds.toIntOrNull() ?: -1, rest = rest.toIntOrNull() ?: -1, notes = notes, builtIn = false)
    Page {
        item { Title(if(id == "new") "Новое упражнение" else initial.name, if(initial.builtIn) "Сохранение создаст вашу версию упражнения" else "Ваше упражнение") }
        OpenExerciseLibrary.sourceInfo(initial.id)?.let { info -> item { Text("Оригинальное название: ${info.englishName} · ${info.level}. Пошаговая инструкция предоставлена на английском из открытого источника.", style = MaterialTheme.typography.bodySmall) } }
        item { ExerciseArt(initial.copy(imageUri = image), Modifier.fillMaxWidth().height(180.dp)) }
        item { OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Выбрать изображение") } }
        item { Field("Название", name, { name = it }) }; item { Choice(categories, category) { category = it } }
        item { Field("Техника выполнения", technique, { technique = it }, lines = 3) }; item { Field("Целевые мышцы", muscles, { muscles = it }) }; item { Field("Оборудование", equipment, { equipment = it }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Column(Modifier.weight(1f)) { Field("Подходы", sets, { sets = it }, true) }; Column(Modifier.weight(1f)) { Field("Повторения", reps, { reps = it }, true) } } }
        item { Field("Рабочий вес, кг", weight, { weight = it }, true) }; item { Field("Выполнение, сек (0 — повторения)", seconds, { seconds = it }, true) }; item { Field("Отдых, сек", rest, { rest = it }, true) }; item { Field("Заметки", notes, { notes = it }, lines = 2) }
        if(error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item { Action("Сохранить упражнение", Icons.Default.Save) { runCatching { build(false).also { BackupCodec.validateExercise(it) } }.onSuccess { e -> vm.exclusive { vm.repo.save(e); back() } }.onFailure { error = "Проверьте название и числа: подходы 1–100, повторения 0–10000, вес 0–2000, отдых 0–3600." } } }
        if(existing != null) item { OutlinedButton(onClick = { runCatching { build(true).also { BackupCodec.validateExercise(it) } }.onSuccess { e -> vm.exclusive { vm.repo.save(e.copy(name = e.name + " · копия")); back() } }.onFailure { error = "Проверьте параметры" } }) { Text("Дублировать упражнение") } }
        if(existing != null && !initial.builtIn) item { TextButton(onClick = { delete = true }) { Text("Удалить упражнение", color = MaterialTheme.colorScheme.error) } }
        val results = history.filter { it.status == "done" }.flatMap { w -> w.items.filter { it.exerciseId == id }.map { w to it } }.sortedByDescending { it.first.date }
        item { Text("История результатов", style = MaterialTheme.typography.titleLarge) }
        if(results.isEmpty()) item { Text("Выполненных подходов пока нет") }
        items(results) { (w, i) -> Panel { Text("${w.date} · ${w.name}"); i.sets.filter { it.done }.forEach { Text("${it.reps} повт. × ${it.weight} кг · ${it.seconds} сек") } } }
    }
    if(delete) Confirm("Удалить упражнение?", "История тренировок сохранится вместе с названием и результатами упражнения.", { delete = false }) { vm.exclusive { vm.repo.deleteExercise(initial.id); back() }; delete = false }
}
@Composable fun SetDialog(s: SetEntry, complete: Boolean, dismiss: () -> Unit, save: (SetEntry) -> Unit) {
    var reps by rememberSaveable(s.id) { mutableStateOf(s.reps.toString()) }
    var weight by rememberSaveable(s.id) { mutableStateOf(s.weight.toString()) }
    var seconds by rememberSaveable(s.id) { mutableStateOf(s.seconds.toString()) }
    var distance by rememberSaveable(s.id) { mutableStateOf(s.distanceMeters.toString()) }
    var rest by rememberSaveable(s.id) { mutableStateOf(s.restSeconds?.toString() ?: "") }
    var rpe by rememberSaveable(s.id) { mutableStateOf(s.rpe?.toString() ?: "") }
    var rir by rememberSaveable(s.id) { mutableStateOf(s.rir?.toString() ?: "") }
    var comment by rememberSaveable(s.id) { mutableStateOf(s.comment) }
    var type by rememberSaveable(s.id) { mutableStateOf(s.type) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if(complete) "Фактический результат" else "Параметры подхода") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(setTypes.values.toList(), setTypes[type] ?: "Обычный") { label -> type = setTypes.entries.first { it.value == label }.key }
            Field("Повторения", reps, { reps = it }, true); Field("Вес, кг", weight, { weight = it }, true)
            Field("Время, сек", seconds, { seconds = it }, true); Field("Дистанция, м", distance, { distance = it }, true)
            Field("Отдых, сек (пусто — как у упражнения)", rest, { rest = it }, true)
            Field("RPE 1–10 (необязательно)", rpe, { rpe = it }, true); Field("RIR 0–10 (необязательно)", rir, { rir = it }, true)
            Field("Комментарий", comment, { comment = it }, lines = 2)
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            fun optional(value: String): Int? = if(value.isBlank()) null else value.toInt()
            s.copy(reps = reps.toInt(), weight = weight.replace(',', '.').toDouble(), seconds = seconds.toInt(), type = type,
                distanceMeters = distance.replace(',', '.').toDouble(), restSeconds = optional(rest), rpe = optional(rpe), rir = optional(rir), comment = comment).also(SessionEdits::validate)
        }.onSuccess(save).onFailure { error = "Проверьте числовые значения, RPE/RIR и отдых (0–3600 сек)" }
    }) { Text(if(complete) "Выполнено" else "Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun WorkoutScreen(vm: FitViewModel, id: String, back: () -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle(); val catalog by vm.exercises.collectAsStateWithLifecycle(); val w = all.find { it.id == id }
    if(w == null) { Empty("Загрузка тренировки…"); return }
    val now = clockNow(); val active = w.status in listOf("active", "paused"); val editable = w.status == "planned"; val modifiable = SessionEdits.editable(w); val done = w.status == "done"
    var add by rememberSaveable { mutableStateOf(false) }; var metadata by rememberSaveable { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var finish by rememberSaveable { mutableStateOf(false) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }; var selectedSetId by rememberSaveable { mutableStateOf<String?>(null) }; var itemSettingsId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedItem = w.items.find { it.id == selectedItemId }; val selectedSet = selectedItem?.sets?.find { it.id == selectedSetId }; val itemSettings = w.items.find { it.id == itemSettingsId }
    val context = LocalContext.current
    var notificationAsked by rememberSaveable(id) { mutableStateOf(false) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(!allowed) vm.notify("Уведомления скрыты настройкой Android; включить их можно в настройках приложения")
        vm.start(w)
    }
    fun startSession() {
        if(Build.VERSION.SDK_INT >= 33 && !notificationAsked && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notificationAsked = true; notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else vm.start(w)
    }
    val stamp = remember(now) { AndroidClock.read(context) }
    val restLeft = SessionTimers.rest(w, stamp)
    var restDialog by rememberSaveable { mutableStateOf(false) }
    var replacementId by remember { mutableStateOf<String?>(null) }
    Page {
        item { Title(w.name, "${w.date} · ${statusLabel(w)}") }
        if(w.notes.isNotBlank()) item { Text(w.notes) }
        if(active || done) item { Panel { Metric(if(done) "Время тренировки" else "Секундомер", formatTime(SessionTimers.elapsed(w, stamp))); Text("${w.completedSets()} / ${w.items.sumOf { it.sets.size }} подходов · %.0f кг объёма".format(w.volume())); if(restLeft > 0) { Metric("Отдых", formatTime(restLeft)); TextButton(enabled = w.status == "active", onClick = { vm.rest(id, 0) }) { Text("Пропустить отдых") } }; if(w.status == "active") TextButton(onClick = { restDialog = true }) { Text("Настроить / запустить отдых") } } }
        if(done) {
            val previous = all.filter { it.id != id && it.status == "done" && it.ended < w.ended && it.items.map { i -> i.exerciseId } == w.items.map { i -> i.exerciseId } }.maxByOrNull { it.ended }
            item { Panel { Text("Тренировка сохранена", style = MaterialTheme.typography.titleLarge); Text("Упражнений с выполненными подходами: ${w.items.count { it.sets.any { s -> s.done } }}"); Text(if(previous == null) "Первый результат для этого состава упражнений." else "К прошлому результату: %+.0f кг объёма · %d подходов".format(w.volume() - previous.volume(), w.completedSets() - previous.completedSets())); Text("Сложность ${w.effort}/10 · восстановление ${w.recovery}/10${if(w.pain) " · отмечена боль" else ""}") } }
        }
        if(editable) item { Action("Название, дата и заметки", Icons.Default.Edit) { metadata = true } }
        if(w.items.isEmpty()) item { Empty("Добавьте упражнения из каталога. Параметры каждого подхода можно изменить отдельно.") }
        val next = w.items.flatMap { i -> i.sets.map { i.id to it } }.firstOrNull { !it.second.done }
        itemsIndexed(w.items, key = { _, i -> i.id }) { index, item ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}. ${item.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if(modifiable) IconButton(onClick = { itemSettingsId = item.id }) { Icon(Icons.Default.Tune, "Настройки упражнения") } }
                if(item.superset.isNotBlank()) Text("Суперсет ${item.superset}", color = MaterialTheme.colorScheme.primary)
                Text("Отдых ${item.rest} сек${if(item.notes.isNotBlank()) " · ${item.notes}" else ""}", style = MaterialTheme.typography.bodySmall)
                item.sets.forEachIndexed { si, s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${si + 1}", Modifier.width(26.dp), fontWeight = FontWeight.Bold)
                        Text("${setTypes[s.type]} · ${s.reps} × ${s.weight} кг${if(s.seconds > 0) " · ${s.seconds} сек" else ""}${if(s.distanceMeters > 0) " · ${s.distanceMeters} м" else ""}${s.rpe?.let { " · RPE $it" } ?: ""}${s.rir?.let { " · RIR $it" } ?: ""}${if(s.comment.isNotBlank()) " · ${s.comment}" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if(s.done) Icon(Icons.Default.CheckCircle, "Выполнено", tint = MaterialTheme.colorScheme.primary)
                        else if(editable || w.status == "active") IconButton(onClick = { selectedItemId = item.id; selectedSetId = s.id }) { Icon(if(editable) Icons.Default.Edit else Icons.Default.CheckCircleOutline, if(editable) "Изменить подход" else "Отметить подход выполненным") }
                        if(editable && item.sets.size > 1) IconButton(onClick = { vm.update(id) { c -> c.copy(items = c.items.map { i -> if(i.id == item.id) i.copy(sets = i.sets.filter { it.id != s.id }) else i }) } }) { Icon(Icons.Default.RemoveCircleOutline, "Удалить подход") }
                    }
                }
                if(active && next?.first == item.id) Text("Текущий подход: ${item.sets.indexOfFirst { it.id == next.second.id } + 1}", color = MaterialTheme.colorScheme.primary)
                if(active && item.sets.any { !it.done && it.seconds > 0 }) {
                    val timed = item.sets.firstOrNull { !it.done && it.seconds > 0 }
                    if(timed != null) TimedSet(w, item, timed, vm)
                }
                if(modifiable) {
                    TextButton(onClick = { vm.update(id) { c -> SessionEdits.addSet(c, item.id) } }) { Text("+ Подход") }
                    TextButton(onClick = { vm.update(id) { SessionEdits.lastResult(it, item.id, all) } }) { Text("Копировать прошлый результат") }
                    TextButton(enabled = item.sets.none { it.done }, onClick = { replacementId = item.id; add = true }) { Text("Заменить упражнение") }
                    Row { IconButton(enabled = index > 0, onClick = { vm.update(id) { c -> SessionEdits.move(c, item.id, -1) } }) { Icon(Icons.Default.ArrowUpward, "Выше") }; IconButton(enabled = index < w.items.lastIndex, onClick = { vm.update(id) { c -> SessionEdits.move(c, item.id, 1) } }) { Icon(Icons.Default.ArrowDownward, "Ниже") }; TextButton(enabled = item.sets.none { it.done }, onClick = { vm.update(id) { c -> require(c.items.find { it.id == item.id }?.sets?.none { it.done } == true); c.copy(items = c.items.filter { it.id != item.id }) } }) { Text("Убрать") } }
                }
            }
        }
        if(modifiable) item { Action("Добавить упражнение") { add = true } }
        if(editable && !w.template && !w.recoveryDay) item { Action("Начать тренировку", Icons.Default.PlayArrow, w.items.isNotEmpty()) { startSession() } }
        if(active) {
            item { Action(if(w.status == "paused") "Продолжить" else "Пауза", if(w.status == "paused") Icons.Default.PlayArrow else Icons.Default.Pause) { if(w.status == "paused") startSession() else vm.pause(w) } }
            item { Action("Завершить и сохранить", Icons.Default.Stop) { finish = true } }
        }
        item { OutlinedButton(onClick = { vm.duplicate(w, onCreated = { vm.notify("Копия создана. Найдите её в календаре.") }) }) { Text(if(w.template) "Создать тренировку из шаблона" else "Дублировать тренировку") } }
        if(!w.template) item { OutlinedButton(onClick = { vm.duplicate(w, template = true) }) { Text("Сохранить как шаблон") } }
        if(editable) item { TextButton(onClick = { delete = true }) { Text("Удалить тренировку", color = MaterialTheme.colorScheme.error) } }
    }
    if(restDialog) RestDialog(vm.profile.value.rest, { restDialog = false }) { vm.rest(id, it); restDialog = false }
    if(add) {
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false; replacementId = null }, title = { Text("Добавить упражнение") }, text = { Column { Field("Поиск", search, { search = it }); LazyColumn(Modifier.heightIn(max = 360.dp)) { items(catalog.filter { it.name.contains(search, true) }) { e -> TextButton(onClick = { val target = replacementId; if(target == null) vm.addExercise(w, e) else vm.update(id) { SessionEdits.replace(it, target, e) }; replacementId = null; add = false }, modifier = Modifier.fillMaxWidth()) { Text(e.name) } } } } }, confirmButton = { TextButton(onClick = { add = false; replacementId = null }) { Text("Закрыть") } })
    }
    if(selectedItem != null && selectedSet != null) SetDialog(selectedSet!!, w.status == "active", { selectedItemId = null; selectedSetId = null }) { entry -> val item = selectedItem!!; if(w.status == "active") vm.completeSet(w, item.id, entry.id, entry) else vm.update(id) { c -> c.copy(items = c.items.map { i -> if(i.id == item.id) i.copy(sets = i.sets.map { if(it.id == entry.id) entry else it }) else i }) }; selectedItemId = null; selectedSetId = null }
    if(metadata) MetadataDialog(w, { metadata = false }) { name, date, notes -> vm.update(id) { it.copy(name = name, date = date, notes = notes) }; metadata = false }
    if(itemSettings != null) ItemDialog(itemSettings!!, { itemSettingsId = null }) { updated -> vm.update(id) { c -> c.copy(items = c.items.map { if(it.id == updated.id) updated else it }) }; itemSettingsId = null }
    if(delete) Confirm("Удалить тренировку?", "Будет удалена только эта плановая тренировка.", { delete = false }) { vm.exclusive { vm.repo.deleteWorkout(id); back() }; delete = false }
    if(finish) FinishDialog({ finish = false }) { effort, recovery, pain -> vm.finish(w, effort, recovery, pain); finish = false }
}
@Composable fun MetadataDialog(w: Workout, dismiss: () -> Unit, save: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(w.name) }; var date by rememberSaveable { mutableStateOf(w.date) }; var notes by rememberSaveable { mutableStateOf(w.notes) }; var error by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("План тренировки") }, text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Название", name, { name = it }); Field("Дата, ГГГГ-ММ-ДД", date, { date = it }); Field("Заметки", notes, { notes = it }, lines = 2); if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if(name.isNotBlank() && name.length <= 200 && runCatching { LocalDate.parse(date) }.isSuccess) save(name.trim(), date, notes) else error = "Введите название и дату в формате 2026-11-01" }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun ItemDialog(i: WorkoutItem, dismiss: () -> Unit, save: (WorkoutItem) -> Unit) {
    var rest by rememberSaveable { mutableStateOf(i.rest.toString()) }; var group by rememberSaveable { mutableStateOf(i.superset) }; var notes by rememberSaveable { mutableStateOf(i.notes) }; var error by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Настройки упражнения") }, text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Отдых, сек", rest, { rest = it }, true); Field("Группа суперсета (например A)", group, { group = it }); Text("Одинаковая группа объединяет упражнения. Для перехода без отдыха задайте 0 секунд.", style = MaterialTheme.typography.bodySmall); Field("Заметки", notes, { notes = it }, lines = 2); if(error) Text("Отдых: целое число от 0 до 3600") } }, confirmButton = { TextButton(onClick = { val r = rest.toIntOrNull(); if(r != null && r in 0..3600) save(i.copy(rest = r, superset = group.trim(), notes = notes)) else error = true }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun FinishDialog(dismiss: () -> Unit, save: (Int, Int, Boolean) -> Unit) {
    var effort by rememberSaveable { mutableFloatStateOf(5f) }; var recovery by rememberSaveable { mutableFloatStateOf(7f) }; var pain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Завершить тренировку") }, text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text("Сохранятся фактически выполненные подходы. Можно завершить досрочно."); Text("Сложность: ${effort.roundToInt()}/10"); Slider(effort, { effort = it }, valueRange = 1f..10f, steps = 8); Text("Восстановление: ${recovery.roundToInt()}/10"); Slider(recovery, { recovery = it }, valueRange = 1f..10f, steps = 8); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pain, { pain = it }); Text("Во время занятия была боль", Modifier.weight(1f)) } } }, confirmButton = { TextButton(onClick = { save(effort.roundToInt(), recovery.roundToInt(), pain) }) { Text("Сохранить результат") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Продолжить занятие") } })
}
@Composable fun RestDialog(initial: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var seconds by rememberSaveable { mutableStateOf(initial.toString()) }; var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Таймер отдыха") }, text = { Column { Field("Секунды", seconds, { seconds = it }, true); if(error) Text("Введите число от 0 до 3600") } }, confirmButton = { TextButton(onClick = { val value = seconds.toIntOrNull(); if(value != null && value in 0..3600) save(value) else error = true }) { Text("Запустить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun TimedSet(w: Workout, item: WorkoutItem, s: SetEntry, vm: FitViewModel) {
    val tick = clockNow(); val context = LocalContext.current; val stamp = remember(tick) { AndroidClock.read(context) }
    if(w.timerSetId == s.id) {
        val left = SessionTimers.stage(w, stamp)
        Text(if(w.status == "paused") "Таймер на паузе: ${formatTime(left)}" else if(left > 0) "Таймер этапа: ${formatTime(left)}" else "Этап завершён · подтвердите фактический результат", color = MaterialTheme.colorScheme.primary)
    } else TextButton(enabled = w.status == "active", onClick = { vm.stage(w.id, s) }) { Icon(Icons.Default.Timer, null); Text("Таймер этапа · ${s.seconds} сек") }
}
@Composable fun CalendarScreen(vm: FitViewModel, open: (String) -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle(); var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var mode by rememberSaveable { mutableStateOf("Неделя") }
    val date = runCatching { LocalDate.parse(dateText) }.getOrDefault(LocalDate.now()); val start = when(mode) { "Месяц" -> date.withDayOfMonth(1); "День" -> date; else -> monday(date) }; val end = when(mode) { "Месяц" -> start.plusMonths(1); "День" -> start.plusDays(1); else -> start.plusDays(7) }
    val planned = all.filter { !it.template && LocalDate.parse(it.date) >= start && LocalDate.parse(it.date) < end }; var recovery by remember { mutableStateOf(false) }; var copy by remember { mutableStateOf(false) }
    Page {
        item { Title("Твой план", "Будущие занятия редактируются отдельно от истории") }
        item { Choice(listOf("День", "Неделя", "Месяц"), mode) { mode = it } }
        item { Field("Дата, ГГГГ-ММ-ДД", dateText, { dateText = it }) }
        if(runCatching { LocalDate.parse(dateText) }.isFailure) item { Text("Введите корректную дату", color = MaterialTheme.colorScheme.error) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { OutlinedButton(onClick = { dateText = when(mode) { "Месяц" -> date.minusMonths(1); "День" -> date.minusDays(1); else -> date.minusWeeks(1) }.toString() }) { Text("Назад") }; OutlinedButton(onClick = { dateText = LocalDate.now().toString() }) { Text("Сегодня") }; OutlinedButton(onClick = { dateText = when(mode) { "Месяц" -> date.plusMonths(1); "День" -> date.plusDays(1); else -> date.plusWeeks(1) }.toString() }) { Text("Вперёд") } } }
        item { Text("$start — ${end.minusDays(1)}", fontWeight = FontWeight.Bold) }
        items((0 until java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()).map { start.plusDays(it.toLong()) }) { day ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale("ru"))), color = MaterialTheme.colorScheme.primary); val list = planned.filter { it.date == day.toString() }; if(list.isEmpty()) Text("Свободный день · восстановление", style = MaterialTheme.typography.bodySmall); list.forEach { w -> WorkoutCard(w) { open(w.id) } } }
        }
        item { Action("Создать тренировку") { vm.exclusive { val w = Workout(date = date.toString()); vm.repo.save(w); open(w.id) } } }
        item { OutlinedButton(onClick = { recovery = true }) { Text("Добавить день восстановления") } }
        item { OutlinedButton(onClick = { copy = true }) { Text("Копировать прошлую неделю в выбранную") } }
        val templates = all.filter { it.template && it.plan == null }
        if(templates.isNotEmpty()) item { Text("Мои шаблоны", style = MaterialTheme.typography.titleLarge) }
        items(templates, key = { "template-${it.id}" }) { w -> WorkoutCard(w) { open(w.id) } }
    }
    if(recovery) Confirm("День восстановления", "Добавить восстановление на $date?", { recovery = false }) { vm.exclusive { vm.repo.save(Workout(name = "Восстановление", date = date.toString(), recoveryDay = true, notes = "Отдых, сон и лёгкая прогулка по самочувствию.")) }; recovery = false }
    if(copy) Confirm("Копировать неделю", "Занятия с ${monday(date).minusWeeks(1)} будут добавлены в неделю с ${monday(date)}. Существующие занятия сохранятся.", { copy = false }) { vm.copyWeek(monday(date).minusWeeks(1), monday(date)); copy = false }
}
@Composable fun ProgramsScreen(vm: FitViewModel, applied: () -> Unit, edit: (String) -> Unit) {
    var direction by rememberSaveable { mutableStateOf("Все") }; var selected by remember { mutableStateOf<Program?>(null) }; var startText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var error by remember { mutableStateOf("") }
    val saved by vm.workouts.collectAsStateWithLifecycle()
    Page {
        item { Action("Создать свою программу", Icons.Default.Add) { vm.createPlan(edit) } }
        items(saved.filter { it.plan != null }, key = { "custom-${it.id}" }) { w -> WorkoutCard(w) { edit(w.id) } }
        item { Title("Программы", "Общие планы. Все занятия можно менять после добавления.") }
        item { Choice(listOf("Все") + Catalog.programs.map { it.direction }.distinct(), direction) { direction = it } }
        items(Catalog.programs.filter { direction == "Все" || it.direction == direction }, key = { it.id }) { p ->
            Panel { Text(p.name, style = MaterialTheme.typography.titleLarge); Text("${p.weeks} недель · ${p.weekdays.size} занятия/неделю"); Text("Дни: ${p.weekdays.joinToString { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1] }}. Остальные — восстановление."); Text(p.exerciseNames.joinToString(" → ")); Text(if(p.direction == "Бег") "Разминка 5 мин · ${p.sets} × (бег ${p.workSeconds} сек + ходьба ${p.restSeconds} сек) · заминка 5 мин. Каждую неделю +10 сек бега." else "${p.sets} подхода × ${p.reps} повторений · отдых ${p.restSeconds} сек${if(p.workSeconds > 0) " · временной этап ${p.workSeconds} сек" else ""}"); Text(p.advice, style = MaterialTheme.typography.bodySmall); Action("Просмотреть и начать", Icons.Default.CalendarMonth) { selected = p } }
        }
    }
    if(selected != null) {
        val p = selected!!; val start = runCatching { LocalDate.parse(startText) }.getOrNull(); val preview = start?.let { Catalog.schedule(p, it) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(p.name) }, text = { Column { Field("Начало, ГГГГ-ММ-ДД", startText, { startText = it }); Text("Будет создано ${preview?.size ?: 0} независимых занятий. Затем откройте любое занятие в календаре, замените упражнения и нагрузку, сохраните как свой шаблон."); preview?.take(3)?.forEach { Text("${it.date}: ${it.items.size} упражнений") }; if(error.isNotBlank()) Text(error) } }, confirmButton = { TextButton(onClick = { if(preview == null) error = "Проверьте дату" else vm.exclusive { vm.repo.saveAll(preview); selected = null; applied() } }) { Text("Добавить в мой план") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Отмена") } })
    }
}
@Composable fun RecommendationsScreen(vm: FitViewModel, applied: () -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    val from = monday().minusWeeks(1); val to = monday().plusWeeks(1); val week = all.filter { !it.template && LocalDate.parse(it.date) >= from && LocalDate.parse(it.date) < from.plusDays(7) }
    val weekRuns = runs.filter { Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault()).toLocalDate().let { d -> d >= from && d < from.plusDays(7) } }
    val engine = remember { LocalRecommendations() }; var chosen by remember { mutableStateOf<Recommendation?>(null) }
    Page {
        item { Title("Следующая неделя", "Анализ завершённой недели: $from — ${from.plusDays(6)}") }
        item { Text("Правила работают локально. Перед изменением вы увидите план и подтвердите его. История не изменяется.") }
        items(engine.suggest(week, weekRuns)) { r -> Panel { Text(r.title, style = MaterialTheme.typography.titleLarge); Text(r.reason); Action(if(r.factor == null) "Открыть календарь" else "Предварительный план", Icons.Default.Preview) { if(r.factor == null) applied() else chosen = r } } }
    }
    if(chosen != null) {
        val r = chosen!!; val preview = engine.preview(week, from, to, r.factor!!)
        AlertDialog(onDismissRequest = { chosen = null }, title = { Text("${r.title} · с $to") }, text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { if(preview.isEmpty()) item { Text("В прошлой неделе нет занятий для копирования. Создайте план вручную или выберите программу.") }; items(preview) { w -> Text("${w.date} · ${w.name}", fontWeight = FontWeight.Bold); w.items.forEach { i -> Text("${i.name}: ${i.sets.joinToString { "${it.reps} × ${it.weight} кг" }}", style = MaterialTheme.typography.bodySmall) }; Spacer(Modifier.height(10.dp)) } } }, confirmButton = { TextButton(enabled = preview.isNotEmpty(), onClick = { vm.exclusive { vm.repo.saveAll(preview); chosen = null; applied() } }) { Text("Подтвердить план") } }, dismissButton = { TextButton(onClick = { chosen = null }) { Text("Отмена") } })
    }
}
@Composable fun RunningScreen(vm: FitViewModel, detail: (String) -> Unit) {
    val runs by vm.runs.collectAsStateWithLifecycle(); val current = runs.firstOrNull { it.status != "done" && !it.demo }; val tick = clockNow(); val context = LocalContext.current
    val stamp = remember(tick) { AndroidClock.read(context) }
    val launch by vm.runLaunch.collectAsStateWithLifecycle()
    val signal by RunService.signal.collectAsStateWithLifecycle(); var finish by remember { mutableStateOf(false) }; var manual by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.runPermissionResult() }
    Page {
        item { Title(if(current?.status == "active") "Пробежка идёт" else "Бег в твоём ритме", "Реальный GPS · запись при заблокированном экране") }
        item { Panel {
            Text(signal, color = MaterialTheme.colorScheme.primary)
            Metric("Дистанция", "%.2f км".format((current?.distance ?: 0.0) / 1000))
            Metric("Время", formatTime(current?.let { RunTimers.elapsed(it, stamp) } ?: 0))
            Column { Metric("Средний темп", formatPace(current?.let { Gps.pace(it.distance, RunTimers.elapsed(it, stamp)) })); Metric("Скорость, км/ч", "%.1f".format(current?.let { Gps.speed(it.distance, RunTimers.elapsed(it, stamp)) } ?: 0.0)) }
            val pace = if(current?.status == "active") Gps.currentPaceAt(current.points, stamp) else null
            Text("Текущий темп: ${formatPace(pace)}"); Text("Текущая скорость: ${pace?.let { "%.1f км/ч".format(60 / it) } ?: "—"}")
        } }
        if(current != null) item { UserMap(vm, current.points) }
        item {
            if(launch.pending) Text(if(launch.phase == RunLaunchPhase.PERMISSION) "Ожидаем разрешение геолокации…" else "Запускаем запись GPS…")
            if(launch.phase == RunLaunchPhase.ERROR) {
                Text(launch.error!!.message, color = MaterialTheme.colorScheme.error)
                if(launch.diagnostic.isNotBlank()) Text("Код: ${launch.diagnostic}", style = MaterialTheme.typography.bodySmall)
                if(launch.error == RunLaunchError.PERMISSION) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("Разрешения приложения") }
            }
        }
        if(current?.status != "active" || launch.phase == RunLaunchPhase.ERROR) item { Action(if(launch.phase == RunLaunchPhase.ERROR) "Повторить запуск" else if(current == null) "Начать пробежку" else "Продолжить пробежку", Icons.Default.PlayArrow, enabled = !launch.pending) {
            vm.requestRunStart { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        } }
        if(current?.status == "active") item { Action("Пауза", Icons.Default.Pause) { runCatching { RunService.command(context, RunService.PAUSE) }.onFailure { vm.notify(it.message ?: "Не удалось поставить на паузу") } } }
        if(current != null) item { Action("Завершить и сохранить", Icons.Default.Stop) { finish = true } }
        item { Text("Без интернета записываются координаты и дистанция. Для первого определения GPS выйдите на открытое место. При потере сигнала расстояние не добавляется.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Настройки геолокации") } }
        item { OutlinedButton(onClick = { manual = true }) { Text("Записать беговую дорожку вручную") } }
        item { Text("История пробежек", style = MaterialTheme.typography.titleLarge) }
        if(runs.none { it.status == "done" }) item { Empty("Сохранённых пробежек пока нет") }
        items(runs.filter { it.status == "done" }, key = { it.id }) { r -> Card(onClick = { detail(r.id) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(if(r.demo) "ДЕМОНСТРАЦИЯ · тестовый маршрут" else if(r.recordingMode == "manual") "Беговая дорожка · введено вручную" else "Пробежка · ${Instant.ofEpochMilli(r.started).atZone(ZoneId.systemDefault()).toLocalDate()}", fontWeight = FontWeight.Bold); Text("%.2f км · %s · %s".format(r.distance / 1000, formatTime(r.accumulatedMs), formatPace(Gps.pace(r.distance, r.accumulatedMs)))) } } }
        item { OutlinedButton(onClick = { vm.demo() }) { Text("Создать отдельный демо-маршрут") } }
    }
    if(manual) ManualRunDialog({ manual = false }) { run -> vm.exclusive { vm.repo.saveRun(run); manual = false; vm.notify("Ручная запись сохранена отдельно от GPS") } }
    if(finish) Confirm("Сохранить пробежку?", "Маршрут и измеренная дистанция сохранятся. Если GPS-точек нет, дистанция останется нулевой.", { finish = false }) { runCatching { RunService.command(context, RunService.FINISH) }.onFailure { vm.notify(it.message ?: "Ошибка сохранения") }; finish = false }
}
@Composable fun RunDetail(vm: FitViewModel, id: String) {
    val all by vm.runs.collectAsStateWithLifecycle(); val r = all.find { it.id == id }
    if(r == null) { Empty("Загрузка маршрута…"); return }
    val splits = remember(r.points) { Gps.splits(r.points) }; val paces = splits.map { it.minutesPerKm }
    Page {
        item { Title(if(r.demo) "Демо-маршрут" else "Сохранённая пробежка", Instant.ofEpochMilli(r.started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru")))) }
        if(r.demo) item { Text("Синтетические координаты. Эта запись исключена из статистики и рекордов.", color = MaterialTheme.colorScheme.primary) }
        item { Panel { Metric("Дистанция", "%.3f км".format(r.distance / 1000)); Metric("Время", formatTime(r.accumulatedMs)); Text("Средний темп: ${formatPace(Gps.pace(r.distance, r.accumulatedMs))}"); Text("Средняя скорость: %.1f км/ч".format(Gps.speed(r.distance, r.accumulatedMs))); Text("GPS-точек: ${r.points.size}"); if(r.recordingMode == "gps") Text("В движении по GPS: ${formatTime(r.movingMs)} (оценка по принятым точкам)") } }
        if(r.recordingMode == "gps") item { UserMap(vm, r.points) } else item { Text("Ручная запись: дистанция и время введены пользователем. GPS-маршрут не записывался.") }
        item { Panel { LineChart(paces, "Темп, мин/км · каждые 100 м дистанции"); Text("По горизонтали — отрезки по 100 м, по вертикали — темп. Паузы исключены; до первых 100 м график пуст.", style = MaterialTheme.typography.bodySmall) } }
    }
}
@Composable fun StatsScreen(vm: FitViewModel, detailed: () -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle(); val exercises by vm.exercises.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf("Месяц") }; var exerciseId by rememberSaveable { mutableStateOf("") }
    val since = when(period) { "Неделя" -> LocalDate.now().minusDays(6); "Месяц" -> LocalDate.now().minusMonths(1); "3 месяца" -> LocalDate.now().minusMonths(3); "Год" -> LocalDate.now().minusYears(1); else -> LocalDate.MIN }
    val done = all.filter { it.status == "done" && it.completedDate() >= since }; val realRuns = runs.filter { it.status == "done" && !it.demo && Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault()).toLocalDate() >= since }
    val gpsDistance = realRuns.filter { it.recordingMode == "gps" }.sumOf { it.distance }; val manualDistance = realRuns.filter { it.recordingMode == "manual" }.sumOf { it.distance }
    val duration = done.sumOf { it.accumulatedMs } + realRuns.sumOf { it.accumulatedMs }; val distance = realRuns.sumOf { it.distance }
    val months = (0..5).map { YearMonth.now().minusMonths((5 - it).toLong()) }
    val usedExercises = done.flatMap { it.items }.distinctBy { it.exerciseId }
    Page {
        item { Action("Расширенная аналитика", Icons.Default.Insights) { detailed() } }
        item { Title("Твой прогресс", "Только сохранённые результаты · демо исключено") }
        item { Choice(listOf("Неделя", "Месяц", "3 месяца", "Год", "Всё время"), period) { period = it } }
        item { Panel { Metric("Всего занятий", (done.size + realRuns.size).toString()); Metric("Общая продолжительность", formatTime(duration)); Metric("Силовой объём", "%.0f кг".format(done.sumOf { it.volume() })); Metric("Километраж", "%.2f км".format(distance / 1000)); Text("GPS: %.2f км · вручную: %.2f км".format(gpsDistance / 1000, manualDistance / 1000)); Text("Средний темп: ${formatPace(Gps.pace(distance, realRuns.sumOf { it.accumulatedMs }))}"); val activeDays = (done.map { it.completedDate().toString() } + realRuns.map { Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault()).toLocalDate().toString() }).distinct().size; Text("Дней с занятиями: $activeDays") } }
        if(done.isEmpty() && realRuns.isEmpty()) item { Empty("В выбранном периоде результатов нет. Завершите тренировку или пробежку.") }
        item { Panel { LineChart(months.map { m -> (done.count { YearMonth.from(it.completedDate()) == m } + realRuns.count { YearMonth.from(Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault())) == m }).toDouble() }, "Частота занятий по месяцам"); Text(months.joinToString(" · ") { it.monthValue.toString() }, style = MaterialTheme.typography.labelSmall) } }
        item { Panel { LineChart(months.map { m -> done.filter { YearMonth.from(it.completedDate()) == m }.sumOf { it.volume() } }, "Силовой объём по месяцам, кг") } }
        if(usedExercises.isNotEmpty()) {
            item { Text("Динамика упражнений", style = MaterialTheme.typography.titleLarge) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { usedExercises.forEach { i -> FilterChip(selected = (exerciseId.ifBlank { usedExercises.first().exerciseId }) == i.exerciseId, onClick = { exerciseId = i.exerciseId }, label = { Text(i.name) }) } } }
            val selected = exerciseId.ifBlank { usedExercises.first().exerciseId }; val entries = done.sortedBy { it.ended }.flatMap { w -> w.items.filter { it.exerciseId == selected }.map { i -> w.completedDate().toString() to (i.sets.filter { it.done }.maxOfOrNull { it.weight } ?: 0.0) } }
            item { Panel { LineChart(entries.map { it.second }, "Максимальный выполненный вес, кг"); Text("Лучший вес: ${entries.maxOfOrNull { it.second } ?: 0.0} кг"); entries.takeLast(10).forEach { Text("${it.first} · ${it.second} кг") } } }
        }
        val best = realRuns.filter { Gps.pace(it.distance, it.accumulatedMs) != null }.minByOrNull { Gps.pace(it.distance, it.accumulatedMs)!! }
        if(best != null) item { Panel { Text("Лучший средний темп пробежки"); Metric("%.2f км".format(best.distance / 1000), formatPace(Gps.pace(best.distance, best.accumulatedMs))); Text("Результат всей пробежки; не время на фиксированной дистанции.", style = MaterialTheme.typography.bodySmall) } }
        item { Panel {
            Text("Лучшее время на дистанции", style = MaterialTheme.typography.titleLarge)
            listOf(1000, 3000, 5000, 10000).forEach { meters ->
                val time = realRuns.mapNotNull { Gps.bestTime(it.points, meters.toDouble()) }.minOrNull()
                Text("${meters / 1000} км: ${time?.let { formatTime(it) } ?: "нет измеренного отрезка"}")
            }
            Text("Непрерывные участки GPS, граница дистанции интерполируется между точками. Точность зависит от GPS.", style = MaterialTheme.typography.bodySmall)
        } }
        val week = all.filter { !it.template && !it.recoveryDay && LocalDate.parse(it.date) >= monday() && LocalDate.parse(it.date) < monday().plusWeeks(1) }
        item { Panel { Text("Выполнение недельного плана: ${week.count { it.status == "done" }} / ${week.size}"); LinearProgressIndicator(progress = { if(week.isEmpty()) 0f else week.count { it.status == "done" }.toFloat() / week.size }, modifier = Modifier.fillMaxWidth()) } }
    }
}
@Composable fun SettingsScreen(vm: FitViewModel) {
    val p by vm.profile.collectAsStateWithLifecycle(); val context = LocalContext.current
    var name by rememberSaveable(p.name) { mutableStateOf(p.name) }; var level by rememberSaveable(p.level) { mutableStateOf(p.level) }; var goals by rememberSaveable(p.goals) { mutableStateOf(p.goals) }; var activities by rememberSaveable(p.activities) { mutableStateOf(p.activities) }; var rest by rememberSaveable(p.rest) { mutableStateOf(p.rest.toString()) }
    var delete by remember { mutableStateOf(false) }; var importData by remember { mutableStateOf<Backup?>(null) }; var importCount by remember { mutableStateOf(0) }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) vm.exclusive {
        val snapshot = vm.repo.snapshot(); val text = withContext(Dispatchers.Default) { BackupCodec.checkedExport(snapshot) }; withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("Файл недоступен для записи") }; vm.notify("JSON сохранён")
    } }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if(uri != null) vm.exclusive {
        val snapshot = vm.repo.snapshot(); val text = withContext(Dispatchers.Default) { BackupCodec.csv(snapshot) }; withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write("\uFEFF"); it.write(text) } ?: error("Файл недоступен для записи") }; vm.notify("CSV сохранён")
    } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.exclusive {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> val output = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); while(true) { val n = input.read(buf); if(n == -1) break; require(output.size() + n <= 20_000_000) { "Файл больше 20 МБ" }; output.write(buf, 0, n) }; output.toString("UTF-8") } ?: error("Не удалось открыть файл") }
        val b = withContext(Dispatchers.Default) { BackupCodec.decode(text.removePrefix("\uFEFF")) }; importCount = b.exercises.size + b.workouts.size + b.runs.size + b.goals.size + b.wellbeing.size; importData = b
    } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> vm.action { vm.repo.updateProfile { it.copy(notifications = allowed) } }; if(!allowed) vm.notify("Уведомления не разрешены") }
    Page {
        item { Title("Профиль и настройки", "Без регистрации · данные на вашем устройстве") }
        item { Field("Имя", name, { name = it }) }
        item { Choice(listOf("Новичок", "Любитель", "Продвинутый"), level) { level = it } }
        item { Field("Спортивные цели", goals, { goals = it }, lines = 2) }; item { Field("Предпочтительные активности", activities, { activities = it }) }; item { Field("Отдых по умолчанию, сек", rest, { rest = it }, true) }
        item { Action("Сохранить профиль", Icons.Default.Save) { val r = rest.toIntOrNull(); if(name.isNotBlank() && name.length <= 80 && r != null && r in 0..3600) vm.action { vm.repo.updateProfile { it.copy(name = name.trim(), level = level, goals = goals, activities = activities, rest = r) }; vm.notify("Профиль сохранён") } else vm.notify("Проверьте имя и отдых (0–3600 секунд)") } }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Светлая тема", Modifier.weight(1f)); Switch(p.light, { light -> vm.action { vm.repo.updateProfile { it.copy(light = light) } } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Недельные уведомления", Modifier.weight(1f)); Switch(p.notifications, { enabled -> if(enabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.action { vm.repo.updateProfile { it.copy(notifications = enabled) } } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Звук таймеров", Modifier.weight(1f)); Switch(p.timerSound, { enabled -> vm.action { vm.repo.updateProfile { it.copy(timerSound = enabled) } } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Вибрация таймеров", Modifier.weight(1f)); Switch(p.timerVibration, { enabled -> vm.action { vm.repo.updateProfile { it.copy(timerVibration = enabled) } } }) }
            Text("Единицы: кг, км, мин/км. Таймеры используют сохранённые сроки, поэтому переживают поворот и перезапуск.", style = MaterialTheme.typography.bodySmall)
        } }
        item { AdditionalSettings(vm) }
        item { Text("Ваши данные", style = MaterialTheme.typography.titleLarge) }
        item { Action("Экспорт резервной копии JSON", Icons.Default.FileDownload) { jsonExport.launch("fitprogress-${LocalDate.now()}.json") } }
        item { Action("Экспорт истории CSV", Icons.Default.TableChart) { csvExport.launch("fitprogress-history-${LocalDate.now()}.csv") } }
        item { OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Импорт резервной копии JSON") } }
        item { Text("Импорт добавляет отсутствующие записи. Совпадающие идентификаторы пропускаются; существующая история не перезаписывается. Изображения из галереи на другом телефоне могут быть недоступны — вместо них показывается локальная иллюстрация.", style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { delete = true }) { Text("Удалить все пользовательские данные", color = MaterialTheme.colorScheme.error) } }
        item { Text("FitProgress AI 1.0 · локальные проверяемые рекомендации. Данные не отправляются на сервер. Для отображения карты загружаются тайлы OpenStreetMap.", style = MaterialTheme.typography.bodySmall) }
    }
    if(importData != null) Confirm("Импорт проверен", "В копии $importCount записей. Добавить их? Сначала завершите активные тренировки и пробежки.", { importData = null }) { val b = importData!!; vm.exclusive { vm.repo.restore(b); importData = null; vm.notify("Импорт завершён; совпадающие записи пропущены") } }
    if(delete) Confirm("Удалить все данные?", "Упражнения, планы, результаты, маршруты и профиль будут удалены без возможности отмены. Встроенный каталог останется. Сначала экспортируйте резервную копию.", { delete = false }) {
        vm.exclusive { val b = vm.repo.snapshot(); require(b.runs.none { it.status != "done" } && b.workouts.none { it.status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }; vm.repo.erase(); delete = false; vm.notify("Пользовательские данные удалены") }
    }
}
