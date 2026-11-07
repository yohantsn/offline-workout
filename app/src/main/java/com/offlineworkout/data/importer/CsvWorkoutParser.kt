package com.offlineworkout.data.importer

import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Result of reading a spreadsheet export.
 *
 * [errors] is all-or-nothing on purpose: a spreadsheet that does not parse
 * cleanly is not half-imported. The repository in T1.3 aborts the whole batch
 * on the first invalid draft, so the parser has to agree with that policy
 * instead of producing a batch that is already doomed.
 */
data class CsvImportResult(
    val workouts: List<WorkoutAggregateDraft>,
    val warnings: List<String>,
    val errors: List<String>
) {
    val canImport: Boolean get() = errors.isEmpty() && workouts.isNotEmpty()
}

/**
 * Reads the layout Google Sheets produces when a periodization grid is
 * exported as CSV: sparse rows, a section title in one cell, a header row with
 * SERIES / REP / DESCANSO far to the right, and exercises whose number and
 * name sit in two adjacent cells.
 *
 * Columns are located by header text, never by a fixed index, so a sheet with
 * two leading empty columns still works. The name column is found per row,
 * because in the export that prompted this the header "EXERCICIOS" sits over
 * the *number* column while the names are one cell to the right.
 *
 * Only SERIES and TIMED are produced. A row whose repetition value is a
 * duration ("5' MIN", "30\"", "1:30") becomes TIMED; anything else with a
 * positive set and rep count becomes SERIES. Weight is read when a PESO/CARGA
 * column exists. Distance is not supported yet and is ignored.
 */
object CsvWorkoutParser {

    private const val MAX_WORKOUTS = 20
    private const val MAX_CODE_LENGTH = 12

    private val numberCell = Regex("^\\d{1,3}$")
    private val repRange = Regex("^(\\d{1,3})\\s*[-–]\\s*(\\d{1,3})$")

    fun parse(bytes: ByteArray, defaultName: String? = null): CsvImportResult =
        parse(String(bytes, StandardCharsets.UTF_8), defaultName)

    fun parse(text: String, defaultName: String? = null): CsvImportResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val content = text.removePrefix("\uFEFF")

        if (content.isBlank()) {
            return CsvImportResult(emptyList(), warnings, listOf("O arquivo está vazio."))
        }

        val delimiter = sniffDelimiter(content)
        val rows = tokenize(content, delimiter)

        val sections = mutableListOf<Section>()
        var current: Section? = null
        var metrics: MetricColumns? = null

        rows.forEachIndexed { rowIndex, cells ->
            val line = rowIndex + 1
            val filled = cells.withIndex().filter { it.value.isNotBlank() }
            if (filled.isEmpty()) return@forEachIndexed

            val normalized = cells.map(::normalizeHeader)

            if (metrics == null && normalized.any { it.startsWith("EXERCIC") }) {
                metrics = metricColumns(normalized)
                return@forEachIndexed
            }
            // The header row repeats for every block of a multi-section sheet.
            if (normalized.any { it.startsWith("EXERCIC") }) {
                metricColumns(normalized)?.let { metrics = it }
                return@forEachIndexed
            }

            val columns = metrics
            val firstCell = filled.first()
            val firstValue = firstCell.value.trim()
            val looksLikeNumber = numberCell.matches(firstValue)

            val hasMetricValue = columns != null && listOf(columns.sets, columns.reps)
                .filter { it >= 0 }
                .any { cells.getOrNull(it)?.isNotBlank() == true }

            if (!looksLikeNumber && !hasMetricValue) {
                // A label with no numbers is a section title: it starts a workout.
                current?.let { previous -> closeSection(previous, sections, warnings) }
                current = Section(title = firstValue, line = line, exercises = mutableListOf(), numbers = mutableListOf())
                return@forEachIndexed
            }

            if (columns == null) {
                errors += "Linha $line: exercício encontrado antes do cabeçalho SERIES/REP."
                return@forEachIndexed
            }

            val code: Int? = if (looksLikeNumber) firstValue.toIntOrNull() else null
            val nameCell = if (looksLikeNumber) {
                filled.firstOrNull { it.index > firstCell.index && !columns.isMetricColumn(it.index) }
            } else {
                firstCell
            }
            val name = nameCell?.value?.trim().orEmpty()
            if (name.isEmpty()) {
                errors += "Linha $line: exercício sem nome."
                return@forEachIndexed
            }

            val section = current ?: Section(
                title = defaultName ?: "Importado",
                line = line,
                exercises = mutableListOf(),
                numbers = mutableListOf()
            ).also { current = it }

            code?.let { section.numbers += it }

            buildExercise(cells, columns, name, line, warnings, errors)?.let { section.exercises += it }
        }

        current?.let { closeSection(it, sections, warnings) }

        if (sections.isEmpty() && errors.isEmpty()) {
            errors += "Nenhum exercício encontrado no arquivo."
        }

        val workouts = mutableListOf<WorkoutAggregateDraft>()
        val usedCodes = mutableSetOf<String>()
        sections.forEach { section ->
            val code = uniqueCode(suggestedCode(section.title), usedCodes, section.title, warnings)
            workouts += WorkoutAggregateDraft(
                code = code,
                name = section.title.trim(),
                exercises = section.exercises
            )
        }

        if (workouts.size > MAX_WORKOUTS) {
            errors += "O arquivo tem ${workouts.size} treinos; o limite por importação é $MAX_WORKOUTS."
        }

        return CsvImportResult(workouts, warnings, errors)
    }

    private fun closeSection(
        section: Section,
        into: MutableList<Section>,
        warnings: MutableList<String>
    ) {
        if (section.exercises.isEmpty()) {
            warnings += "Seção '${section.title}' (linha ${section.line}) não tem exercícios e foi ignorada."
            return
        }
        val gaps = numberingGaps(section.numbers)
        if (gaps.isNotEmpty()) {
            warnings += "Seção '${section.title}': numeração pula ${gaps.joinToString(", ")}."
        }
        into += section
    }

    private fun numberingGaps(numbers: List<Int>): List<String> {
        if (numbers.size < 2) return emptyList()
        val min = numbers.min()
        val max = numbers.max()
        val present = numbers.toSet()
        return (min..max).filter { it !in present }.map { "%02d".format(it) }
    }

    private fun buildExercise(
        cells: List<String>,
        columns: MetricColumns,
        name: String,
        line: Int,
        warnings: MutableList<String>,
        errors: MutableList<String>
    ): ExerciseAggregateDraft? {
        fun cell(index: Int): String = if (index >= 0) cells.getOrNull(index)?.trim().orEmpty() else ""

        val setsRaw = cell(columns.sets)
        val repsRaw = cell(columns.reps)
        val restRaw = cell(columns.rest)
        val weightRaw = cell(columns.weight)

        val rest = parseRestSeconds(restRaw, line, warnings)
        if (rest == null) {
            errors += "Linha $line: descanso '$restRaw' não reconhecido."
            return null
        }

        val duration = parseDurationSeconds(repsRaw)
        if (duration != null && duration > 0L) {
            if (setsRaw.isNotBlank()) {
                warnings += "Linha $line: '$name' é por tempo; o valor de SERIES '$setsRaw' não é representável em TIMED e foi ignorado."
            }
            return ExerciseAggregateDraft(
                id = null,
                name = name,
                type = ExerciseType.TIMED,
                restSeconds = rest,
                targetSets = null,
                targetReps = null,
                targetWeightKg = null,
                targetDurationSeconds = duration,
                targetDistanceMeters = null
            )
        }

        val reps = parseReps(repsRaw, line, warnings)
        val sets = setsRaw.toIntOrNull()

        if (reps == null || reps <= 0 || sets == null || sets <= 0) {
            errors += "Linha $line: '$name' precisa de séries e repetições válidas (SERIES='$setsRaw', REP='$repsRaw')."
            return null
        }

        return ExerciseAggregateDraft(
            id = null,
            name = name,
            type = ExerciseType.SERIES,
            restSeconds = rest,
            targetSets = sets,
            targetReps = reps,
            targetWeightKg = parseWeight(weightRaw),
            targetDurationSeconds = null,
            targetDistanceMeters = null
        )
    }

    private fun parseReps(raw: String, line: Int, warnings: MutableList<String>): Int? {
        if (raw.isBlank()) return null
        raw.toIntOrNull()?.let { return it }
        val range = repRange.find(raw)
        if (range != null) {
            val low = range.groupValues[1].toInt()
            warnings += "Linha $line: repetições '$raw' é um intervalo; usando o limite inferior ($low)."
            return low
        }
        return null
    }

    private fun parseWeight(raw: String): Double? {
        if (raw.isBlank()) return null
        return raw.replace("kg", "", ignoreCase = true).trim().replace(',', '.').toDoubleOrNull()
    }

    private fun parseRestSeconds(raw: String, line: Int, warnings: MutableList<String>): Int? {
        if (raw.isBlank()) {
            warnings += "Linha $line: descanso vazio; assumido 0."
            return 0
        }
        parseDurationSeconds(raw)?.let { return it.toInt() }
        val plain = raw.replace(',', '.').toDoubleOrNull()
        if (plain != null) {
            warnings += "Linha $line: descanso '$raw' sem unidade; assumido $plain segundos."
            return plain.toInt()
        }
        return null
    }

    /**
     * Accepts `5'`, `5'30"`, `5 min`, `30"`, `30s`, `90 seg`, `1:30` and
     * `1:05:30`. Returns null when the value is not a duration, which is the
     * signal that the cell holds a repetition count instead.
     */
    private fun parseDurationSeconds(raw: String): Long? {
        val text = raw.trim().lowercase().replace(" ", "")
        if (text.isEmpty()) return null

        val clock = Regex("^\\d{1,2}:\\d{1,2}(:\\d{1,2})?$")
        if (clock.matches(text)) {
            val parts = text.split(':').map { it.toLong() }
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> null
            }
        }

        val minutesAndSeconds = Regex("^(\\d{1,3})'(?:(\\d{1,2})\")?(?:min|minutos?)?$").find(text)
        if (minutesAndSeconds != null) {
            val minutes = minutesAndSeconds.groupValues[1].toLong()
            val seconds = minutesAndSeconds.groupValues[2].toLongOrNull() ?: 0L
            return minutes * 60 + seconds
        }

        val seconds = Regex("^(\\d{1,4})(?:\"|''|s|seg|segundos?)$").find(text)
        if (seconds != null) return seconds.groupValues[1].toLong()

        val minutes = Regex("^(\\d{1,3})(?:min|minutos?|m)$").find(text)
        if (minutes != null) return minutes.groupValues[1].toLong() * 60

        return null
    }

    private fun suggestedCode(title: String): String {
        val head = title.substringBefore('/').trim()
        val cleaned = normalizeHeader(head)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_CODE_LENGTH)
        return cleaned.ifEmpty { "IMPORT" }
    }

    private fun uniqueCode(
        base: String,
        used: MutableSet<String>,
        sectionTitle: String,
        warnings: MutableList<String>
    ): String {
        if (used.add(base)) return base
        var suffix = 2
        while (!used.add("$base-$suffix")) suffix++
        val code = "$base-$suffix"
        warnings += "Código '$base' repetido; a seção '$sectionTitle' ficou como '$code'."
        return code
    }

    private fun normalizeHeader(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase()

    private fun metricColumns(normalized: List<String>): MetricColumns? {
        val sets = normalized.indexOfFirst { it == "SERIES" || it == "SETS" }
        val reps = normalized.indexOfFirst { it.startsWith("REP") }
        val rest = normalized.indexOfFirst { it.startsWith("DESCANSO") || it == "REST" }
        val weight = normalized.indexOfFirst { it == "PESO" || it == "CARGA" }
        if (sets < 0 && reps < 0) return null
        return MetricColumns(sets, reps, rest, weight)
    }

    /** Picks the delimiter that produces the most multi-field rows. */
    private fun sniffDelimiter(text: String): Char {
        return listOf(',', ';', '\t').maxByOrNull { candidate ->
            val rows = tokenize(text, candidate)
            val multiField = rows.count { it.size > 1 }
            multiField * 1000 + (rows.maxOfOrNull { it.size } ?: 0)
        } ?: ','
    }

    private fun tokenize(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"'); index += 2
                    }
                    char == '"' -> { quoted = false; index++ }
                    else -> { field.append(char); index++ }
                }
                continue
            }
            when (char) {
                '"' -> { quoted = true; index++ }
                delimiter -> { endField(); index++ }
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRow(); index++
                }
                '\n' -> { endRow(); index++ }
                else -> { field.append(char); index++ }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private data class Section(
        val title: String,
        val line: Int,
        val exercises: MutableList<ExerciseAggregateDraft>,
        val numbers: MutableList<Int>
    )

    private data class MetricColumns(
        val sets: Int,
        val reps: Int,
        val rest: Int,
        val weight: Int
    ) {
        fun isMetricColumn(index: Int): Boolean =
            index == sets || index == reps || index == rest || index == weight
    }
}
