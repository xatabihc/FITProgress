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
    private data class RussianGuide(val technique: String, val errors: String, val alternatives: String)
    private val russianGuides = mapOf(
        "Pushups" to RussianGuide("Примите упор лёжа: ладони примерно под плечами, тело удерживайте прямой линией. Согните руки, опуская грудь под контролем; оттолкнитесь и выпрямите руки без резкого запирания локтей.", "Не прогибайте поясницу, не поднимайте таз и не разводите локти строго в стороны.", "Отжимания от высокой опоры или с колен; усложнение — узкая постановка рук."),
        "Pullups" to RussianGuide("Возьмитесь за перекладину устойчивым хватом. Начните движение сведением лопаток, подтяните грудь к перекладине без раскачивания и плавно опуститесь до комфортного разгибания рук.", "Не раскачивайтесь, не вытягивайте подбородок вместо подъёма и не падайте в нижнюю точку.", "Тяга верхнего блока или подтягивания с резиновой поддержкой."),
        "Barbell_Bench_Press_-_Medium_Grip" to RussianGuide("Лягте так, чтобы гриф находился над глазами; стопы устойчиво стоят на полу. Сведите лопатки, снимите гриф, опустите его к нижней части груди с предплечьями примерно вертикально и выжмите вверх.", "Не отбивайте гриф от груди, не теряйте опору стоп и не допускайте неконтролируемого разведения локтей.", "Жим гантелей лёжа или отжимания. Для тяжёлых подходов используйте страховочные упоры или партнёра."),
        "Dumbbell_Bench_Press" to RussianGuide("Лягте на скамью, удерживая гантели над грудью. Опускайте их по сторонам груди в контролируемой амплитуде, сохраняя запястья над локтями; выжмите вверх без столкновения гантелей.", "Не переразгибайте поясницу, не опускайте плечо в болезненную глубину и не сводите гантели ударом.", "Жим штанги или отжимания от пола."),
        "Barbell_Full_Squat" to RussianGuide("Поставьте стопы устойчиво, направив носки по естественному углу. Отведите таз и согните колени, удерживая всю стопу на полу и корпус собранным; встаньте, одновременно разгибая таз и колени.", "Не теряйте равновесие на носках, не округляйте поясницу и не форсируйте глубину, которую не контролируете.", "Приседание к коробке, приседание с собственным весом или жим ногами."),
        "Barbell_Deadlift" to RussianGuide("Подойдите к грифу так, чтобы он был над серединой стопы. Зафиксируйте корпус, возьмитесь за гриф и поднимайте его близко к ногам, разгибая колени и таз согласованно; опускайте по той же траектории.", "Не округляйте спину под нагрузкой, не отводите гриф от ног и не дёргайте его с пола.", "Тяга гири с возвышения или румынская тяга с лёгким весом."),
        "Bent_Over_Barbell_Row" to RussianGuide("Удерживая корпус наклонённым и спину нейтральной, подтяните гриф к нижней части живота. Кратко сведите лопатки и опустите гриф под контролем.", "Не поднимайте корпус на каждом повторе и не округляйте поясницу.", "Тяга гантели с опорой или тяга горизонтального блока."),
        "Plank" to RussianGuide("Опирайтесь на предплечья и носки, локти расположите под плечами. Напрягите живот и ягодицы, сохраняя прямую линию от головы до пяток; дышите ровно и завершите подход до потери положения.", "Не провисайте в пояснице, не поднимайте таз слишком высоко и не задерживайте дыхание.", "Планка с колен или короткие удержания у стены."),
        "Barbell_Curl" to RussianGuide("Встаньте устойчиво, держите гриф на вытянутых руках. Согните локти, поднимая гриф без движения плеч; плавно опустите до исходного положения.", "Не раскачивайте корпус, не уводите локти вперёд и не разгибайте локоть рывком.", "Сгибание рук с гантелями или на нижнем блоке."),
        "Standing_Military_Press" to RussianGuide("Поставьте стопы устойчиво и напрягите корпус. Начните с грифа у верхней части груди, выжмите его над головой по близкой к лицу траектории и верните под контролем.", "Не переразгибайте поясницу и не продолжайте через боль в плече.", "Жим гантелей сидя с лёгким весом или жим landmine."),
        "Romanian_Deadlift" to RussianGuide("Держите вес перед бёдрами. Отведите таз назад с мягко согнутыми коленями, опуская вес вдоль ног до выраженного, но контролируемого натяжения задней поверхности бедра; вернитесь за счёт разгибания таза.", "Не превращайте движение в присед и не округляйте спину ради большей глубины.", "Наклон-тазовый шарнир без веса или тяга гири с возвышения."),
        "Full_Range-Of-Motion_Lat_Pulldown" to RussianGuide("Сядьте, зафиксируйте бёдра под упорами и возьмитесь за рукоять. Опустите плечи, затем потяните рукоять к верхней части груди и медленно верните вверх.", "Не отклоняйтесь далеко назад, не тяните за голову и не бросайте вес в верхней точке.", "Подтягивания с резиной или тяга блока нейтральным хватом."),
        "Leg_Press" to RussianGuide("Расположите стопы на платформе так, чтобы колени двигались по направлению носков. Опускайте платформу до глубины, при которой таз и поясница остаются прижатыми; выжмите, не запирая колени.", "Не отрывайте таз от спинки и не сводите колени внутрь.", "Приседание к опоре или сплит-присед без веса."),
        "Lying_Leg_Curls" to RussianGuide("Настройте валик чуть выше пяток и лягте ровно. Согните колени, подтягивая валик к бёдрам, затем медленно разогните ноги.", "Не поднимайте таз и не бросайте валик в нижней точке.", "Сгибание ног сидя или ягодичный мост с шагами.")
    )
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
                val guide = russianGuides[sourceId]
                ExerciseSourceMetadata.put(id, ExerciseSourceInfo(level(text(row, "level")), text(row, "category"), english, listOf(sourceId.replace('_', ' '), sourceId.replace('_', ' ').replace('-', ' '), english, originalGear) + englishMuscles,
                    primaryMuscles = primaryRu.joinToString(", "), secondaryMuscles = secondaryRu.joinToString(", "), commonErrors = guide?.errors.orEmpty(), alternatives = guide?.alternatives.orEmpty(), localizedGuide = guide != null))
                Exercise(id = id, name = russianNames[sourceId] ?: english, category = category,
                    technique = guide?.technique ?: instructionList.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                    muscles = (primaryRu + secondaryRu).distinct().joinToString(", "),
                    equipment = if(originalGear.isBlank() || gearEn == "unspecified") "Не указано источником" else gear[gearEn.lowercase(Locale.ROOT)] ?: gearEn.replaceFirstChar { it.uppercase() },
                    sets = 3, reps = 10, seconds = 0, rest = 60,
                    notes = "Открытая запись: ${text(row, "level").ifBlank { "уровень не указан" }} · ${if(guide == null) "исходная инструкция на английском" else "краткая русская редакция FitProgressAI"}. Это общая справка, не медицинская рекомендация.", builtIn = true)
            }.getOrNull() }
            require(rows.map { it.id }.distinct().size == rows.size) { "Exercise dataset has duplicate identifiers" }
            Log.i("FitExerciseLibrary", "Loaded ${rows.size} unique offline exercises from $SOURCE@$REVISION")
            rows.also { cache = it }
        }
    }
}

