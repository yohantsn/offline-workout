package com.offlineworkout.data.importer

import com.offlineworkout.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWorkoutParserTest {

    private fun realFile(): ByteArray =
        javaClass.getResourceAsStream("/importer/aquecimento.csv")!!.readBytes()

    private fun line(vararg cells: String): String = line(',', *cells)

    private fun line(delimiter: Char, vararg cells: String): String = cells.joinToString(delimiter.toString()) { cell ->
        if (cell.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }
    }

    private val header = line("", "EXERCICIOS", "", "", "", "", "SERIES", "", "REP", "", "DESCANSO")

    private fun seriesRow(number: String, name: String, sets: String, reps: String, rest: String): String =
        line("", number, name, "", "", "", sets, "", reps, "", rest)

    private fun sheet(title: String, exercise: String): String =
        listOf(line("", title), header, exercise).joinToString("\n")

    @Test
    fun parses_the_real_export_into_one_workout_with_four_exercises() {
        val result = CsvWorkoutParser.parse(realFile())

        assertTrue(result.errors.toString(), result.canImport)
        assertEquals(1, result.workouts.size)

        val workout = result.workouts.single()
        assertEquals("AQUECIMENTO/CARDIO/MOBILIDADE", workout.name)
        assertEquals("AQUECIMENTO", workout.code)
        assertEquals(4, workout.exercises.size)

        assertEquals(
            listOf(
                "CARDIO LEVE (BIKE)",
                "PONTE DE GLÚTEO",
                "REMADA COM ELÁSTICO EM PÉ",
                "AGACHAMENTO NA CAIXA (ENCOSTAR QUADRIL E SUBIR, MOVIMENTO CURTO DAS PERNAS)"
            ),
            workout.exercises.map { it.name }
        )
    }

    @Test
    fun the_cardio_row_becomes_timed_because_its_reps_are_a_duration() {
        val cardio = CsvWorkoutParser.parse(realFile()).workouts.single().exercises.first()

        assertEquals(ExerciseType.TIMED, cardio.type)
        assertEquals(300L, cardio.targetDurationSeconds)
        assertEquals(0, cardio.restSeconds)
        assertEquals(null, cardio.targetSets)
        assertEquals(null, cardio.targetReps)
    }

    @Test
    fun the_series_rows_keep_sets_reps_and_quoted_rest_in_seconds() {
        val rows = CsvWorkoutParser.parse(realFile()).workouts.single().exercises.drop(1)

        rows.forEach { row ->
            assertEquals(row.name, ExerciseType.SERIES, row.type)
            assertEquals(row.name, 2, row.targetSets)
            assertEquals(row.name, 10, row.targetReps)
            assertEquals(row.name, 20, row.restSeconds)
        }
    }

    @Test
    fun warns_about_the_missing_number_02_instead_of_renumbering() {
        val result = CsvWorkoutParser.parse(realFile())

        assertTrue(
            result.warnings.toString(),
            result.warnings.any { it.contains("numeração pula 02") }
        )
        assertEquals(4, result.workouts.single().exercises.size)
    }

    @Test
    fun warns_when_a_set_count_is_dropped_to_make_a_timed_exercise_valid() {
        val result = CsvWorkoutParser.parse(realFile())

        assertTrue(
            result.warnings.toString(),
            result.warnings.any { it.contains("não é representável em TIMED") }
        )
    }

    @Test
    fun creates_one_workout_per_section_with_unique_codes() {
        val text = listOf(
            sheet("AQUECIMENTO", seriesRow("01", "AGACHAMENTO", "2", "10", "20\"")),
            sheet("TREINO A", seriesRow("01", "SUPINO", "3", "12", "60\""))
        ).joinToString("\n")

        val result = CsvWorkoutParser.parse(text)

        assertTrue(result.errors.toString(), result.canImport)
        assertEquals(listOf("AQUECIMENTO", "TREINOA"), result.workouts.map { it.code })
        assertEquals(listOf("AQUECIMENTO", "TREINO A"), result.workouts.map { it.name })
    }

    @Test
    fun disambiguates_repeated_section_codes_and_says_so() {
        val text = listOf(
            sheet("TREINO", seriesRow("01", "AGACHAMENTO", "2", "10", "20\"")),
            sheet("TREINO", seriesRow("01", "SUPINO", "3", "12", "60\""))
        ).joinToString("\n")

        val result = CsvWorkoutParser.parse(text)

        assertEquals(listOf("TREINO", "TREINO-2"), result.workouts.map { it.code })
        assertTrue(result.warnings.any { it.contains("Código 'TREINO' repetido") })
    }

    @Test
    fun ignores_a_section_that_has_no_exercises() {
        val text = line("", "VAZIO") + "\n" +
            sheet("TREINO", seriesRow("01", "AGACHAMENTO", "2", "10", "20\""))

        val result = CsvWorkoutParser.parse(text)

        assertEquals(1, result.workouts.size)
        assertEquals("TREINO", result.workouts.single().name)
        assertTrue(result.warnings.any { it.contains("não tem exercícios") })
    }

    @Test
    fun an_exercise_without_sets_or_reps_blocks_the_whole_import() {
        val text = listOf(
            line("", "TREINO"),
            header,
            line("", "01", "AGACHAMENTO", "", "", "", "", "", "", "", "")
        ).joinToString("\n")

        val result = CsvWorkoutParser.parse(text)

        assertFalse(result.canImport)
        assertTrue(result.errors.any { it.contains("séries e repetições válidas") })
    }

    @Test
    fun refuses_more_than_the_repository_limit_of_workouts() {
        val text = (0 until 21)
            .joinToString("\n") { index ->
                sheet("TREINO $index", seriesRow("01", "AGACHAMENTO", "2", "10", "20\""))
            }

        val result = CsvWorkoutParser.parse(text)

        assertFalse(result.canImport)
        assertTrue(result.errors.any { it.contains("limite por importação é 20") })
    }

    @Test
    fun accepts_semicolon_delimited_exports() {
        val text = listOf(
            line(';', "", "AQUECIMENTO"),
            line(';', "", "EXERCICIOS", "", "", "", "", "SERIES", "", "REP", "", "DESCANSO"),
            line(';', "", "01", "AGACHAMENTO", "", "", "", "2", "", "10", "", "20\"")
        ).joinToString("\n")

        val result = CsvWorkoutParser.parse(text)

        assertTrue(result.errors.toString(), result.canImport)
        val exercise = result.workouts.single().exercises.single()
        assertEquals("AGACHAMENTO", exercise.name)
        assertEquals(2, exercise.targetSets)
        assertEquals(10, exercise.targetReps)
        assertEquals(20, exercise.restSeconds)
    }

    @Test
    fun strips_a_utf8_bom_before_reading_the_first_cell() {
        val text = "\uFEFF" + line("", "AQUECIMENTO") + "\n" +
            header + "\n" +
            seriesRow("01", "AGACHAMENTO", "2", "10", "20\"")

        val result = CsvWorkoutParser.parse(text)

        assertTrue(result.errors.toString(), result.canImport)
        assertEquals("AQUECIMENTO", result.workouts.single().code)
    }

    @Test
    fun takes_the_lower_bound_of_a_repetition_range_and_warns() {
        val result = CsvWorkoutParser.parse(sheet("TREINO", seriesRow("01", "SUPINO", "3", "10-12", "60\"")))

        assertEquals(10, result.workouts.single().exercises.single().targetReps)
        assertTrue(result.warnings.any { it.contains("intervalo") })
    }

    @Test
    fun empty_file_is_an_error_not_an_empty_import() {
        val result = CsvWorkoutParser.parse("   \n  ")

        assertFalse(result.canImport)
        assertTrue(result.errors.any { it.contains("vazio") })
    }

    @Test
    fun unquoted_rest_without_a_unit_is_read_as_seconds_and_flagged() {
        val result = CsvWorkoutParser.parse(sheet("TREINO", seriesRow("01", "SUPINO", "3", "10", "45")))

        assertEquals(45, result.workouts.single().exercises.single().restSeconds)
        assertTrue(result.warnings.any { it.contains("sem unidade") })
    }
}
