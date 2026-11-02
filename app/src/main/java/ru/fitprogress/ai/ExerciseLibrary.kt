package ru.fitprogress.ai

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** Immutable offline snapshot. Source descriptions remain labelled as English source text. */
object OpenExerciseLibrary {
    const val SOURCE = "yuhonas/free-exercise-db"
    const val REVISION = "f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5"
    const val LICENSE = "Unlicense"
    @Volatile private var cache: List<Exercise>? = null
    private val russianNames = mapOf(
        "3_4_Sit-Up" to "Подъёмы корпуса на 3/4", "90_90_Hamstring" to "Растяжка задней поверхности бедра 90/90",
        "Ab_Crunch_Machine" to "Скручивания в тренажёре", "Ab_Roller" to "Выкатывание ролика для пресса",
        "Air_Bike" to "Воздушный велотренажёр", "Alternate_Incline_Dumbbell_Curl" to "Попеременные сгибания гантелей на наклонной скамье",
        "Barbell_Bench_Press_-_Medium_Grip" to "Жим штанги лёжа средним хватом", "Barbell_Full_Squat" to "Приседание со штангой",
        "Barbell_Deadlift" to "Становая тяга со штангой", "Barbell_Row" to "Тяга штанги в наклоне",
        "Bent_Over_Barbell_Row" to "Тяга штанги в наклоне", "Dumbbell_Bench_Press" to "Жим гантелей лёжа",
        "Dumbbell_Flyes" to "Разведение гантелей лёжа", "Dumbbell_Row" to "Тяга гантели одной рукой",
        "Dumbbell_Squat" to "Приседание с гантелями", "Pullups" to "Подтягивания",
        "Pushups" to "Отжимания", "Push-Up" to "Отжимания", "Plank" to "Планка",
        "Barbell_Curl" to "Сгибание рук со штангой", "Standing_Calf_Raises" to "Подъёмы на носки стоя",
        "Romanian_Deadlift" to "Румынская тяга", "Front_Squat" to "Фронтальное приседание",
        "Overhead_Press" to "Жим штанги над головой", "Military_Press" to "Армейский жим",
        "Lat_Pulldown" to "Тяга верхнего блока", "Seated_Cable_Rows" to "Тяга блока сидя",
        "Dips_-_Triceps_Version" to "Отжимания на брусьях с акцентом на трицепс",
        "Cable_Crossover" to "Сведение рук в кроссовере", "Leg_Press" to "Жим ногами в тренажёре",
        "Leg_Extensions" to "Разгибание ног в тренажёре", "Lying_Leg_Curls" to "Сгибание ног лёжа",
        "Hip_Thrust" to "Ягодичный мост со штангой", "Walking_Lunge" to "Выпады в ходьбе",
        "Jumping_Jacks" to "Прыжки с разведением рук и ног", "Burpee" to "Берпи", "Burpees" to "Берпи"
    )
    private val muscles = mapOf(
        "chest" to "грудные мышцы", "middle back" to "средняя часть спины", "lower back" to "поясница", "lats" to "широчайшие",
        "shoulders" to "плечи", "biceps" to "бицепс", "triceps" to "трицепс", "forearms" to "предплечья", "abdominals" to "пресс",
        "quadriceps" to "квадрицепсы", "hamstrings" to "задняя поверхность бедра", "glutes" to "ягодичные мышцы", "calves" to "икры",
        "adductors" to "приводящие мышцы", "abductors" to "отводящие мышцы", "neck" to "мышцы шеи"
    )
    private val gear = mapOf(
        "body only" to "без оборудования", "dumbbell" to "гантели", "barbell" to "штанга", "kettlebells" to "гири",
        "machine" to "тренажёр", "cable" to "блочный тренажёр", "bands" to "эспандер", "band" to "эспандер",
        "foam roll" to "ролл для массажа", "medicine ball" to "медбол", "exercise ball" to "фитбол", "e-z curl bar" to "EZ-гриф",
        "other" to "другое оборудование", "none" to "без оборудования"
    )
    private fun text(o: JsonObject, key: String) = o.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun array(o: JsonObject, key: String) = o.getAsJsonArray(key)?.mapNotNull { if(it.isJsonNull) null else it.asString }.orEmpty()
    private fun level(value: String) = when(value.lowercase(Locale.ROOT)) { "beginner" -> "Начинающий"; "intermediate" -> "Средний"; "expert", "advanced" -> "Продвинутый"; else -> "Не указан источником" }
    private fun category(primary: List<String>, category: String): String {
        if(category.equals("cardio", true)) return "Кардио"
        if(category.equals("stretching", true)) return "Растяжка"
        val muscle = primary.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        return when(muscle) {
            "chest", "pectorals" -> "Грудь"
            "lats", "middle back" -> "Широчайшие и верх спины"
            "lower back" -> "Нижняя часть спины"
            "shoulders" -> "Плечи"
            "biceps" -> "Бицепс"
            "triceps" -> "Трицепс"
            "forearms" -> "Предплечья"
            "abdominals" -> "Пресс"
            "quadriceps" -> "Квадрицепсы"
            "hamstrings" -> "Задняя поверхность бедра"
            "glutes" -> "Ягодицы"
            "calves" -> "Икры"
            "adductors" -> "Ноги"
            else -> "Всё тело"
        }
    }
    fun sourceInfo(id: String): ExerciseSourceInfo? = ExerciseSourceMetadata.get(id)
    fun entries(context: Context): List<Exercise> {
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            val root = JsonParser.parseReader(context.assets.open("exercises-open.json").bufferedReader()).asJsonObject
            require(text(root, "revision") == REVISION && text(root, "license") == LICENSE)
            val rows = root.getAsJsonArray("exercises").mapNotNull { node -> runCatching {
                val row = node.asJsonObject
                val sourceId = text(row, "id")
                val english = text(row, "name").trim()
                val instructionList = array(row, "instructions").map(String::trim).filter(String::isNotBlank)
                val primary = array(row, "primaryMuscles")
                if(sourceId.isBlank() || english.isBlank() || instructionList.isEmpty() || primary.isEmpty()) return@runCatching null
                val id = "open-$sourceId"
                val gearEn = text(row, "equipment").ifBlank { "unspecified" }
                val primaryRu = primary.map { muscles[it.lowercase(Locale.ROOT)] ?: it }
                val secondaryRu = array(row, "secondaryMuscles").map { muscles[it.lowercase(Locale.ROOT)] ?: it }
                val category = category(primary, text(row, "category"))
                val originalGear = text(row, "equipment")
                val englishMuscles = primary + array(row, "secondaryMuscles")
                ExerciseSourceMetadata.put(id, ExerciseSourceInfo(level(text(row, "level")), text(row, "mechanic").ifBlank { text(row, "category") }, english, listOf(sourceId.replace('_', ' '), sourceId.replace('_', ' ').replace('-', ' '), english, originalGear) + englishMuscles))
                Exercise(id = id, name = russianNames[sourceId] ?: english, category = category,
                    technique = instructionList.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                    muscles = (primaryRu + secondaryRu).distinct().joinToString(", "),
                    equipment = gear[gearEn.lowercase(Locale.ROOT)] ?: gearEn.replaceFirstChar { it.uppercase() },
                    sets = 3, reps = 10, seconds = 0, rest = 60,
                    notes = "Открытая запись: ${text(row, "level").ifBlank { "уровень не указан" }} · инструкция на английском. Сверьте технику с тренером при сомнении.", builtIn = true)
            }.getOrNull() }
            require(rows.map { it.id }.distinct().size == rows.size) { "Exercise dataset has duplicate identifiers" }
            Log.i("FitExerciseLibrary", "Loaded ${rows.size} unique offline exercises from $SOURCE@$REVISION")
            rows.also { cache = it }
        }
    }
}

