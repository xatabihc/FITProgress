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

class ExerciseSearchIndex(exercises: List<Exercise>) {
    private data class Indexed(val exercise: Exercise, val text: String, val meta: ExerciseSourceInfo?)
    private val indexed = exercises.map { e ->
        val meta = ExerciseSourceMetadata.get(e.id)
        Indexed(e, foldSearch(listOf(e.name, e.category, e.muscles, e.equipment, e.technique, meta?.englishName.orEmpty(), meta?.aliases.orEmpty().joinToString(" ")).joinToString(" ")), meta)
    }
    val size get() = indexed.size
    fun filter(query: String = "", category: String = "Все", equipment: String = "Любое", level: String = "Любой", type: String = "Любой тип", origin: String = "Все источники"): List<Exercise> {
        val terms = foldSearch(query).split(' ').filter(String::isNotBlank)
        return indexed.asSequence().filter { row ->
            (category == "Все" || row.exercise.category == category) && (equipment == "Любое" || row.exercise.equipment == equipment) &&
                (level == "Любой" || row.meta?.level == level) && (type == "Любой тип" || row.meta?.type.equals(type, true)) &&
                (origin == "Все источники" || if(origin == "Встроенные") row.exercise.builtIn else !row.exercise.builtIn) && terms.all { it in row.text }
        }.map { it.exercise }.toList()
    }
}
