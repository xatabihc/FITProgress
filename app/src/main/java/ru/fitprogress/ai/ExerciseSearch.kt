package ru.fitprogress.ai

import java.util.Locale

data class ExerciseSourceInfo(
    val level: String, val type: String, val englishName: String, val aliases: List<String>,
    val primaryMuscles: String = "", val secondaryMuscles: String = "", val commonErrors: String = "",
    val safety: String = "Выполняйте в контролируемой амплитуде; при боли остановитесь.", val alternatives: String = "",
    val localizedGuide: Boolean = false
)
object ExerciseSourceMetadata {
    private val values = HashMap<String, ExerciseSourceInfo>()
    @Synchronized fun put(id: String, value: ExerciseSourceInfo) { values[id] = value }
    @Synchronized fun get(id: String): ExerciseSourceInfo? = values[id]
}

private fun foldSearch(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace('_', ' ').replace('-', ' ').trim().split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ")

// UI labels are localized, but exercise metadata keeps stable English identifiers.
// Never persist translated labels in place of these identifiers.
private fun canonicalExerciseType(value: String): String = when (foldSearch(value)) {
    "силовые", "силовая", "силовой", "strength" -> "strength"
    "кардио", "cardio" -> "cardio"
    "растяжка", "стретчинг", "stretching" -> "stretching"
    else -> foldSearch(value)
}

class ExerciseSearchIndex(exercises: List<Exercise>) {
    private data class Indexed(
        val exercise: Exercise,
        val text: String,
        val meta: ExerciseSourceInfo?,
        val categoryKey: String,
        val equipmentKey: String,
        val typeKey: String
    )
    private val indexed = exercises.map { e ->
        val meta = ExerciseSourceMetadata.get(e.id)
        Indexed(
            e,
            foldSearch(listOf(e.name, e.category, e.muscles, e.equipment, e.technique, meta?.englishName.orEmpty(), meta?.aliases.orEmpty().joinToString(" ")).joinToString(" ")),
            meta,
            foldSearch(e.category),
            foldSearch(e.equipment),
            canonicalExerciseType(meta?.type.orEmpty())
        )
    }
    val size get() = indexed.size

    fun filter(query: String = "", category: String = "Все", equipment: String = "Любое", level: String = "Любой", type: String = "Любой тип", origin: String = "Все источники"): List<Exercise> {
        val terms = foldSearch(query).split(' ').filter(String::isNotBlank)
        val categoryKey = foldSearch(category)
        val equipmentKey = foldSearch(equipment)
        val typeKey = canonicalExerciseType(type)
        return indexed.asSequence().filter { row ->
            (categoryKey == "все" || row.categoryKey == categoryKey) &&
                (equipmentKey == "любое" || row.equipmentKey == equipmentKey) &&
                (foldSearch(level) == "любой" || foldSearch(row.meta?.level.orEmpty()) == foldSearch(level)) &&
                (typeKey == "любой тип" || row.typeKey == typeKey) &&
                (origin == "Все источники" || if (origin == "Встроенные") row.exercise.builtIn else !row.exercise.builtIn) &&
                terms.all { it in row.text }
        }.map { it.exercise }.toList()
    }
}
