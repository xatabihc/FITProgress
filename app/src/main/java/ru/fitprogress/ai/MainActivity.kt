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
    val dark = darkColorScheme(primary = Accent, onPrimary = Color(0xFF162309), background = Color(0xFF101410), surface = Color(0xFF191F19), surfaceVariant = Color(0xFF283028))
    val light = lightColorScheme(primary = Color(0xFF3C6A12), background = Color(0xFFF5F8F1), surface = Color.White)
    MaterialTheme(colorScheme = if(profile.light) light else dark, typography = Typography()) {
        val nav = rememberNavController(); val back by nav.currentBackStackEntryAsState(); val route = back?.destination?.route ?: "home"
        val snackbar = remember { SnackbarHostState() }; val busy by vm.busy.collectAsStateWithLifecycle()
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if(route !in listOf("home", "catalog", "calendar", "running", "stats")) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Column(Modifier.weight(1f)) { Text("FITPROGRESS", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp); Text("Персональный ритм · 1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { if(route != "settings") nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Настройки") }
            }
        }, bottomBar = {
            NavigationBar {
                listOf(Triple("home", "Главная", Icons.Default.Home), Triple("catalog", "Каталог", Icons.Default.FitnessCenter), Triple("calendar", "План", Icons.Default.CalendarMonth), Triple("running", "Бег", Icons.Default.DirectionsRun), Triple("stats", "Прогресс", Icons.Default.Insights)).forEach { (path, name, icon) ->
                    NavigationBarItem(selected = route == path, onClick = { nav.navigate(path) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, name) }, label = { Text(name, fontSize = 10.sp, maxLines = 1) })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(vm, { nav.navigate("workout/$it") }, { nav.navigate(it) }) }
                    composable("catalog") { CatalogScreen(vm, { nav.navigate("exercise/$it") }) }
                    composable("exercise/{id}") { ExerciseScreen(vm, it.arguments?.getString("id") ?: "new", { nav.popBackStack() }) }
                    composable("workout/{id}") { WorkoutScreen(vm, it.arguments?.getString("id") ?: "", { nav.popBackStack() }) }
                    composable("calendar") { CalendarScreen(vm, { nav.navigate("workout/$it") }) }
                    composable("programs") { ProgramsScreen(vm, { nav.navigate("calendar") }) }
                    composable("running") { RunningScreen(vm, { nav.navigate("run/$it") }) }
                    composable("run/{id}") { RunDetail(vm, it.arguments?.getString("id") ?: "") }
                    composable("stats") { StatsScreen(vm) }
                    composable("settings") { SettingsScreen(vm) }
                    composable("recommendations") { RecommendationsScreen(vm, { nav.navigate("calendar") }) }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}
@Composable fun Page(content: LazyListScope.() -> Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
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
fun statusLabel(w: Workout) = if(w.template) "Шаблон" else when(w.status) { "done" -> "Завершено"; "active" -> "Выполняется"; "paused" -> "На паузе"; else -> "В плане" }
@Composable fun WorkoutCard(w: Workout, open: () -> Unit) { Card(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if(w.status == "done") Icons.Default.CheckCircle else Icons.Default.FitnessCenter, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(w.name, fontWeight = FontWeight.Bold); Text("${w.date} · ${statusLabel(w)}", style = MaterialTheme.typography.labelMedium); Text("${w.items.size} упр. · ${w.items.sumOf { it.sets.size }} подходов", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } }
@Composable fun HomeScreen(vm: FitViewModel, open: (String) -> Unit, navigate: (String) -> Unit) {
    val p by vm.profile.collectAsStateWithLifecycle(); val all by vm.workouts.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    val today = LocalDate.now(); val start = monday(); val week = all.filter { !it.template && LocalDate.parse(it.date) >= start && LocalDate.parse(it.date) < start.plusDays(7) }; val done = week.filter { it.status == "done" }
    val weekRuns = runs.filter { !it.demo && it.status == "done" && Instant.ofEpochMilli(it.started).atZone(ZoneId.systemDefault()).toLocalDate() >= start }
    val active = all.firstOrNull { it.status in listOf("active", "paused") }; val next = active ?: all.firstOrNull { !it.template && it.status == "planned" && LocalDate.parse(it.date) >= today }
    Page {
        item { Title("Привет, ${p.name}", today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")))) }
        item { Panel { Text("ТВОЯ НЕДЕЛЯ", letterSpacing = 2.sp, style = MaterialTheme.typography.labelMedium); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("занятий", (done.size + weekRuns.size).toString()); Metric("время", "${(done.sumOf { it.accumulatedMs } + weekRuns.sumOf { it.accumulatedMs }) / 60000} мин") }; Metric("дистанция бега", "%.2f км".format(weekRuns.sumOf { it.distance } / 1000)); LinearProgressIndicator(progress = { if(week.isEmpty()) 0f else done.size.toFloat() / week.size }, modifier = Modifier.fillMaxWidth()); Text("План: ${done.size} / ${week.size} тренировок") } }
        item { Text(if(active != null) "Продолжить тренировку" else "Следующая тренировка", style = MaterialTheme.typography.titleLarge) }
        item { if(next != null) WorkoutCard(next) { open(next.id) } else Empty("Пока нет запланированных занятий. Создайте первую тренировку.") }
        item { Action("Создать тренировку") { vm.create(open) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { navigate("programs") }, modifier = Modifier.weight(1f)) { Text("Программы") }; OutlinedButton(onClick = { navigate("catalog") }, modifier = Modifier.weight(1f)) { Text("Упражнения") } } }
        val programs = all.filter { it.program.isNotBlank() && it.status != "done" && !it.template }.groupBy { it.program }
        if(programs.isNotEmpty()) item { Panel { Text("Активные программы", style = MaterialTheme.typography.titleLarge); programs.forEach { (id, list) -> Text("${Catalog.programs.find { it.id == id }?.name ?: id}: ${list.size} занятий впереди") } } }
        val recent = all.filter { it.status == "done" }.maxByOrNull { it.ended }
        if(recent != null) item { Panel { Text("Последний результат", style = MaterialTheme.typography.titleLarge); Text("${recent.name}: ${recent.completedSets()} подходов · ${"%.0f".format(recent.volume())} кг объёма"); TextButton(onClick = { open(recent.id) }) { Text("Открыть историю") } } }
        item { Action("План следующей недели", Icons.Default.AutoGraph) { navigate("recommendations") } }
    }
}
@Composable fun CatalogScreen(vm: FitViewModel, edit: (String) -> Unit) {
    val exercises by vm.exercises.collectAsStateWithLifecycle(); var query by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("Все") }
    Page {
        item { Title("Движение — основа", "Каталог упражнений · работает без интернета") }
        item { Field("Поиск упражнения", query, { query = it }) }
        item { Choice(listOf("Все") + categories, category) { category = it } }
        item { Action("Создать упражнение") { edit("new") } }
        items(exercises.filter { (category == "Все" || it.category == category) && it.name.contains(query, true) }, key = { it.id }) { e ->
            Card(onClick = { edit(e.id) }, shape = RoundedCornerShape(22.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { ExerciseArt(e, Modifier.size(76.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(e.name, fontWeight = FontWeight.Bold); Text(e.category, color = MaterialTheme.colorScheme.primary); Text("${e.sets} × ${if(e.seconds > 0) "${e.seconds} сек" else "${e.reps} повт"} · ${e.equipment}", style = MaterialTheme.typography.bodySmall) } } }
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
    var reps by remember(s.id) { mutableStateOf(s.reps.toString()) }; var weight by remember(s.id) { mutableStateOf(s.weight.toString()) }; var seconds by remember(s.id) { mutableStateOf(s.seconds.toString()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if(complete) "Фактический результат" else "Параметры подхода") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Field("Повторения", reps, { reps = it }, true); Field("Вес, кг", weight, { weight = it }, true); Field("Время, сек", seconds, { seconds = it }, true); if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { val r = reps.toIntOrNull(); val w = weight.replace(',', '.').toDoubleOrNull(); val t = seconds.toIntOrNull(); if(r != null && r in 0..10000 && w != null && w.isFinite() && w in 0.0..2000.0 && t != null && t in 0..86400) save(s.copy(reps = r, weight = w, seconds = t)) else error = "Проверьте числовые значения" }) { Text(if(complete) "Выполнено" else "Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun WorkoutScreen(vm: FitViewModel, id: String, back: () -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle(); val catalog by vm.exercises.collectAsStateWithLifecycle(); val w = all.find { it.id == id }
    if(w == null) { Empty("Загрузка тренировки…"); return }
    val now = clockNow(); val active = w.status in listOf("active", "paused"); val editable = w.status == "planned"; val done = w.status == "done"
    var add by rememberSaveable { mutableStateOf(false) }; var metadata by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var finish by rememberSaveable { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<WorkoutItem?>(null) }; var selectedSet by remember { mutableStateOf<SetEntry?>(null) }; var itemSettings by remember { mutableStateOf<WorkoutItem?>(null) }
    val context = LocalContext.current
    var previousRest by remember(id) { mutableLongStateOf(0) }
    LaunchedEffect(now, w.restUntil) { if(previousRest > 0 && w.restUntil > 0 && now >= w.restUntil && previousRest < w.restUntil) { (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)); previousRest = w.restUntil }; if(previousRest == 0L && w.restUntil > 0) previousRest = w.restUntil - 1 }
    Page {
        item { Title(w.name, "${w.date} · ${statusLabel(w)}") }
        if(w.notes.isNotBlank()) item { Text(w.notes) }
        if(active || done) item { Panel { Metric(if(done) "Время тренировки" else "Секундомер", formatTime(w.elapsed(now))); Text("${w.completedSets()} / ${w.items.sumOf { it.sets.size }} подходов · %.0f кг объёма".format(w.volume())); if(w.restUntil > now && w.status == "active") { Metric("Отдых", formatTime(w.restUntil - now)); TextButton(onClick = { vm.update(id) { it.copy(restUntil = 0) } }) { Text("Пропустить отдых") } } } }
        if(done) {
            val previous = all.filter { it.id != id && it.status == "done" && it.ended < w.ended && it.items.map { i -> i.exerciseId } == w.items.map { i -> i.exerciseId } }.maxByOrNull { it.ended }
            item { Panel { Text("Тренировка сохранена", style = MaterialTheme.typography.titleLarge); Text("Упражнений с выполненными подходами: ${w.items.count { it.sets.any { s -> s.done } }}"); Text(if(previous == null) "Первый результат для этого состава упражнений." else "К прошлому результату: %+.0f кг объёма · %d подходов".format(w.volume() - previous.volume(), w.completedSets() - previous.completedSets())); Text("Сложность ${w.effort}/10 · восстановление ${w.recovery}/10${if(w.pain) " · отмечена боль" else ""}") } }
        }
        if(editable) item { Action("Название, дата и заметки", Icons.Default.Edit) { metadata = true } }
        if(w.items.isEmpty()) item { Empty("Добавьте упражнения из каталога. Параметры каждого подхода можно изменить отдельно.") }
        val next = w.items.flatMap { i -> i.sets.map { i.id to it } }.firstOrNull { !it.second.done }
        itemsIndexed(w.items, key = { _, i -> i.id }) { index, item ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}. ${item.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if(editable) IconButton(onClick = { itemSettings = item }) { Icon(Icons.Default.Tune, "Настройки упражнения") } }
                if(item.superset.isNotBlank()) Text("Суперсет ${item.superset}", color = MaterialTheme.colorScheme.primary)
                Text("Отдых ${item.rest} сек${if(item.notes.isNotBlank()) " · ${item.notes}" else ""}", style = MaterialTheme.typography.bodySmall)
                item.sets.forEachIndexed { si, s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${si + 1}", Modifier.width(26.dp), fontWeight = FontWeight.Bold)
                        Text("${s.reps} повт. × ${s.weight} кг${if(s.seconds > 0) " · ${s.seconds} сек" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if(s.done) Icon(Icons.Default.CheckCircle, "Выполнено", tint = MaterialTheme.colorScheme.primary)
                        else if(editable || w.status == "active") IconButton(onClick = { selectedItem = item; selectedSet = s }) { Icon(if(editable) Icons.Default.Edit else Icons.Default.CheckCircleOutline, if(editable) "Изменить подход" else "Отметить подход выполненным") }
                        if(editable && item.sets.size > 1) IconButton(onClick = { vm.update(id) { c -> c.copy(items = c.items.map { i -> if(i.id == item.id) i.copy(sets = i.sets.filter { it.id != s.id }) else i }) } }) { Icon(Icons.Default.RemoveCircleOutline, "Удалить подход") }
                    }
                }
                if(active && next?.first == item.id) Text("Текущий подход: ${item.sets.indexOfFirst { it.id == next.second.id } + 1}", color = MaterialTheme.colorScheme.primary)
                if(active && item.sets.any { !it.done && it.seconds > 0 }) {
                    val timed = item.sets.firstOrNull { !it.done && it.seconds > 0 }
                    if(timed != null) TimedSet(w, item, timed, vm)
                }
                if(editable) {
                    TextButton(onClick = { vm.update(id) { c -> c.copy(items = c.items.map { i -> if(i.id == item.id) i.copy(sets = i.sets + (i.sets.lastOrNull() ?: SetEntry()).copy(id = newId(), done = false)) else i }) } }) { Text("+ Подход") }
                    Row { IconButton(enabled = index > 0, onClick = { vm.update(id) { c -> val list = c.items.toMutableList(); val pos = list.indexOfFirst { it.id == item.id }; if(pos > 0) java.util.Collections.swap(list, pos, pos - 1); c.copy(items = list) } }) { Icon(Icons.Default.ArrowUpward, "Выше") }; IconButton(enabled = index < w.items.lastIndex, onClick = { vm.update(id) { c -> val list = c.items.toMutableList(); val pos = list.indexOfFirst { it.id == item.id }; if(pos >= 0 && pos < list.lastIndex) java.util.Collections.swap(list, pos, pos + 1); c.copy(items = list) } }) { Icon(Icons.Default.ArrowDownward, "Ниже") }; TextButton(onClick = { vm.update(id) { c -> c.copy(items = c.items.filter { it.id != item.id }) } }) { Text("Убрать") } }
                }
            }
        }
        if(editable) item { Action("Добавить упражнение") { add = true } }
        if(editable && !w.template) item { Action("Начать тренировку", Icons.Default.PlayArrow, w.items.isNotEmpty()) { vm.start(w) } }
        if(active) {
            item { Action(if(w.status == "paused") "Продолжить" else "Пауза", if(w.status == "paused") Icons.Default.PlayArrow else Icons.Default.Pause) { if(w.status == "paused") vm.start(w) else vm.pause(w) } }
            item { Action("Завершить и сохранить", Icons.Default.Stop) { finish = true } }
        }
        item { OutlinedButton(onClick = { vm.duplicate(w, onCreated = { vm.notify("Копия создана. Найдите её в календаре.") }) }) { Text(if(w.template) "Создать тренировку из шаблона" else "Дублировать тренировку") } }
        if(!w.template) item { OutlinedButton(onClick = { vm.duplicate(w, template = true) }) { Text("Сохранить как шаблон") } }
        if(editable) item { TextButton(onClick = { delete = true }) { Text("Удалить тренировку", color = MaterialTheme.colorScheme.error) } }
    }
    if(add) {
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("Добавить упражнение") }, text = { Column { Field("Поиск", search, { search = it }); LazyColumn(Modifier.heightIn(max = 360.dp)) { items(catalog.filter { it.name.contains(search, true) }) { e -> TextButton(onClick = { vm.addExercise(w, e); add = false }, modifier = Modifier.fillMaxWidth()) { Text(e.name) } } } } }, confirmButton = { TextButton(onClick = { add = false }) { Text("Закрыть") } })
    }
    if(selectedItem != null && selectedSet != null) SetDialog(selectedSet!!, w.status == "active", { selectedItem = null; selectedSet = null }) { entry -> val item = selectedItem!!; if(w.status == "active") vm.completeSet(w, item.id, entry.id, entry) else vm.update(id) { c -> c.copy(items = c.items.map { i -> if(i.id == item.id) i.copy(sets = i.sets.map { if(it.id == entry.id) entry else it }) else i }) }; selectedItem = null; selectedSet = null }
    if(metadata) MetadataDialog(w, { metadata = false }) { name, date, notes -> vm.update(id) { it.copy(name = name, date = date, notes = notes) }; metadata = false }
    if(itemSettings != null) ItemDialog(itemSettings!!, { itemSettings = null }) { updated -> vm.update(id) { c -> c.copy(items = c.items.map { if(it.id == updated.id) updated else it }) }; itemSettings = null }
    if(delete) Confirm("Удалить тренировку?", "Будет удалена только эта плановая тренировка.", { delete = false }) { vm.exclusive { vm.repo.deleteWorkout(id); back() }; delete = false }
    if(finish) FinishDialog({ finish = false }) { effort, recovery, pain -> vm.finish(w, effort, recovery, pain); finish = false }
}
@Composable fun MetadataDialog(w: Workout, dismiss: () -> Unit, save: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(w.name) }; var date by remember { mutableStateOf(w.date) }; var notes by remember { mutableStateOf(w.notes) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("План тренировки") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Название", name, { name = it }); Field("Дата, ГГГГ-ММ-ДД", date, { date = it }); Field("Заметки", notes, { notes = it }, lines = 2); if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if(name.isNotBlank() && name.length <= 200 && runCatching { LocalDate.parse(date) }.isSuccess) save(name.trim(), date, notes) else error = "Введите название и дату в формате 2026-11-01" }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun ItemDialog(i: WorkoutItem, dismiss: () -> Unit, save: (WorkoutItem) -> Unit) {
    var rest by remember { mutableStateOf(i.rest.toString()) }; var group by remember { mutableStateOf(i.superset) }; var notes by remember { mutableStateOf(i.notes) }; var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Настройки упражнения") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Отдых, сек", rest, { rest = it }, true); Field("Группа суперсета (например A)", group, { group = it }); Text("Одинаковая группа объединяет упражнения. Для перехода без отдыха задайте 0 секунд.", style = MaterialTheme.typography.bodySmall); Field("Заметки", notes, { notes = it }, lines = 2); if(error) Text("Отдых: целое число от 0 до 3600") } }, confirmButton = { TextButton(onClick = { val r = rest.toIntOrNull(); if(r != null && r in 0..3600) save(i.copy(rest = r, superset = group.trim(), notes = notes)) else error = true }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable fun FinishDialog(dismiss: () -> Unit, save: (Int, Int, Boolean) -> Unit) {
    var effort by rememberSaveable { mutableFloatStateOf(5f) }; var recovery by rememberSaveable { mutableFloatStateOf(7f) }; var pain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Завершить тренировку") }, text = { Column { Text("Сохранятся фактически выполненные подходы. Можно завершить досрочно."); Text("Сложность: ${effort.roundToInt()}/10"); Slider(effort, { effort = it }, valueRange = 1f..10f, steps = 8); Text("Восстановление: ${recovery.roundToInt()}/10"); Slider(recovery, { recovery = it }, valueRange = 1f..10f, steps = 8); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pain, { pain = it }); Text("Во время занятия была боль", Modifier.weight(1f)) } } }, confirmButton = { TextButton(onClick = { save(effort.roundToInt(), recovery.roundToInt(), pain) }) { Text("Сохранить результат") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Продолжить занятие") } })
}
@Composable fun TimedSet(w: Workout, item: WorkoutItem, s: SetEntry, vm: FitViewModel) {
    val now = clockNow(); val context = LocalContext.current
    var signaled by rememberSaveable(s.id) { mutableLongStateOf(0) }
    if(w.timerSetId == s.id && w.timerUntil > 0) {
        Text("Таймер этапа: ${formatTime(max(0, w.timerUntil - now))}", color = MaterialTheme.colorScheme.primary)
        LaunchedEffect(now, w.timerUntil) { if(now >= w.timerUntil && signaled != w.timerUntil) { signaled = w.timerUntil; (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)); vm.notify("Этап завершён: ${item.name}. Подтвердите фактический результат.") } }
    } else if(w.timerSetId == s.id && w.status == "paused") Text("Таймер на паузе: ${formatTime(w.timerRemaining)}")
    else TextButton(enabled = w.status == "active", onClick = { vm.update(w.id) { it.copy(timerSetId = s.id, timerUntil = System.currentTimeMillis() + s.seconds * 1000L, timerRemaining = 0) } }) { Icon(Icons.Default.Timer, null); Text("Таймер этапа · ${s.seconds} сек") }
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
        val templates = all.filter { it.template }
        if(templates.isNotEmpty()) item { Text("Мои шаблоны", style = MaterialTheme.typography.titleLarge) }
        items(templates, key = { "template-${it.id}" }) { w -> WorkoutCard(w) { open(w.id) } }
    }
    if(recovery) Confirm("День восстановления", "Добавить восстановление на $date?", { recovery = false }) { vm.exclusive { vm.repo.save(Workout(name = "Восстановление", date = date.toString(), notes = "Отдых, сон и лёгкая прогулка по самочувствию.")) }; recovery = false }
    if(copy) Confirm("Копировать неделю", "Занятия с ${monday(date).minusWeeks(1)} будут добавлены в неделю с ${monday(date)}. Существующие занятия сохранятся.", { copy = false }) { vm.copyWeek(monday(date).minusWeeks(1), monday(date)); copy = false }
}
@Composable fun ProgramsScreen(vm: FitViewModel, applied: () -> Unit) {
    var direction by rememberSaveable { mutableStateOf("Все") }; var selected by remember { mutableStateOf<Program?>(null) }; var startText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var error by remember { mutableStateOf("") }
    Page {
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
