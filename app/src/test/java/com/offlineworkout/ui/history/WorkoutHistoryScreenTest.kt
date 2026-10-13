package com.offlineworkout.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import com.offlineworkout.ui.MissingRecord
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.HistoryChartData
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.viewmodel.ExerciseHistoryUiState
import com.offlineworkout.viewmodel.HistoryError
import com.offlineworkout.data.repository.RepositoryFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T7.1: history screen states: selection, summary + chart + table, paging,
// errors, empty and accessibility semantics. Stateless, no Room.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutHistoryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun seriesSpec(id: Long, name: String) = ExerciseSpec(
        id = id, workoutId = 7L, name = name, type = ExerciseType.SERIES,
        position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
        targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun execution(id: Long, performedAt: Long) = ExerciseExecutionView(
        id = id,
        exerciseId = 1L,
        performedAt = performedAt,
        completedAt = performedAt + 10L,
        durationSeconds = null,
        distanceMeters = null,
        sets = listOf(ExecutionSetView(1, 10, 60.0))
    )

    private fun chart() = HistoryChartData(
        type = ExerciseType.SERIES,
        unitPrimary = "kg",
        unitSecondary = null,
        points = listOf(
            com.offlineworkout.data.model.HistoryPoint(1000L, 1L, 1, 50.0, null, 10),
            com.offlineworkout.data.model.HistoryPoint(2000L, 1L, 1, 60.0, null, 10)
        ),
        wasDownsampled = false,
        totalPointCount = 2
    )

    private fun stats() = HistoryStats(1L, 2, 1000L, 2000L)

    private class Calls {
        val selected = mutableListOf<Long>()
        var cleared = 0
        var more = 0
        var retried = 0
        var backed = 0
    }

    private fun idleScreen(state: ExerciseHistoryUiState, calls: Calls = Calls()) {
        composeTestRule.setContent {
            WorkoutHistoryScreen(
                state = state,
                onSelectExercise = { calls.selected.add(it) },
                onClearExercise = { calls.cleared += 1 },
                onLoadMore = { calls.more += 1 },
                onRetry = { calls.retried += 1 },
                onBack = { calls.backed += 1 }
            )
        }
    }

    private fun ready(
        exercises: List<ExerciseSpec> = listOf(seriesSpec(1L, "Squat")),
        selected: Long? = 1L,
        rows: List<ExerciseExecutionView> = listOf(execution(10L, 2000L), execution(9L, 1000L)),
        chartData: HistoryChartData? = chart(),
        statsData: HistoryStats? = stats(),
        hasMore: Boolean = false,
        loadingMore: Boolean = false,
        error: HistoryError? = null
    ) = ExerciseHistoryUiState.Ready(
        workoutName = "Alpha",
        exercises = exercises,
        selectedExerciseId = selected,
        rows = rows,
        chart = chartData,
        stats = statsData,
        hasMore = hasMore,
        isLoadingMore = loadingMore,
        isDownsampled = chartData?.wasDownsampled == true,
        error = error
    )

    @Test
    fun loading_notfound_and_error() {
        idleScreen(ExerciseHistoryUiState.Loading)
        composeTestRule.onNodeWithText("Carregando histórico…").assertExists()
    }

    @Test
    fun notfound_state_names_the_missing_record() {
        // T9.3 (bloqueio 7): NotFound is emitted for a missing workout and for
        // a missing exercise; the screen used to always accuse the exercise.
        idleScreen(ExerciseHistoryUiState.NotFound(MissingRecord.EXERCISE))
        composeTestRule.onNodeWithText("Exercício não encontrado.").assertExists()
        composeTestRule.onNodeWithText("Voltar para treinos").assertExists()
    }

    @Test
    fun notfound_says_workout_when_the_workout_is_gone() {
        idleScreen(ExerciseHistoryUiState.NotFound(MissingRecord.WORKOUT))
        composeTestRule.onNodeWithText("Treino não encontrado.").assertExists()
        composeTestRule.onAllNodesWithText("Exercício não encontrado.").assertCountEquals(0)
    }

    @Test
    fun notfound_block_is_announced() {
        // The destination is unexpected, so it is announced assertively and
        // the only focusable element is the way back.
        idleScreen(ExerciseHistoryUiState.NotFound(MissingRecord.WORKOUT))
        val announced = SemanticsMatcher("assertive live region") { node ->
            var assertive = false
            for ((key, value) in node.config) {
                if (key == SemanticsProperties.LiveRegion) {
                    assertive = value == LiveRegionMode.Assertive
                }
            }
            assertive
        }
        composeTestRule.onNode(hasText("Treino não encontrado.") and hasAnyAncestor(announced))
            .assertExists()
    }

    @Test
    fun blocking_error_with_retry() {
        val calls = Calls()
        idleScreen(
            ExerciseHistoryUiState.Error(RepositoryFailure.Storage("down")),
            calls
        )
        composeTestRule.onNodeWithText("Não foi possível carregar o histórico.").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.retried) }
    }

    @Test
    fun selection_lists_exercises_without_query() {
        val calls = Calls()
        idleScreen(ready(selected = null, rows = emptyList(), chartData = null, statsData = null), calls)
        composeTestRule.onNodeWithText("Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Squat").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(1L), calls.selected) }
    }

    @Test
    fun summary_chart_table_together() {
        idleScreen(ready())
        composeTestRule.onNodeWithText("Alpha").assertExists()
        // Summary with count and last metric.
        composeTestRule.onNodeWithText("2 execuções • primeira", substring = true).assertExists()
        composeTestRule.onNodeWithText("último peso: 60.0 kg", substring = true).assertExists()
        // Chart description carries type, unit and count.
        composeTestRule.onNodeWithContentDescription("Gráfico de carga em kg com 2 pontos").assertExists()
        // Legend + axis caption.
        composeTestRule.onNodeWithText("Carga (kg)").assertExists()
        // Table content lives below summary + chart: scroll the rows into view.
        // Both fixture executions share set values, so the row text appears twice.
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(4)
        composeTestRule.onAllNodesWithText("Série 1: 10 reps • 60.0 kg", substring = true).assertCountEquals(2)
    }

    @Test
    fun downsample_note_and_pager() {
        val calls = Calls()
        val downsampled = chart().copy(
            points = chart().points,
            wasDownsampled = true,
            totalPointCount = 500
        )
        idleScreen(
            ready(
                exercises = listOf(seriesSpec(1L, "One"), seriesSpec(2L, "Two")),
                chartData = downsampled
            ),
            calls
        )
        composeTestRule.onNodeWithText("Gráfico simplificado para 2 de 500 pontos").assertExists()
        // O aviso também vai no subtítulo do card, com período e unidade.
        val period = "${formatHistoryDate(1000L)} – ${formatHistoryDate(2000L)}"
        composeTestRule.onNodeWithText("$period · kg · 2 de 500 pontos").assertExists()
        // O seletor virou chips: tocar no chip seleciona direto, sem Anterior/Próximo.
        composeTestRule.onNodeWithContentDescription("Ver histórico de Two").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(2L), calls.selected) }
    }

    @Test
    fun load_more_only_when_has_more() {
        val calls = Calls()
        idleScreen(ready(hasMore = true), calls)
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(6)
        composeTestRule.onNodeWithText("Carregar mais").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.more) }
    }

    @Test
    fun no_load_more_without_has_more() {
        idleScreen(ready(hasMore = false))
        composeTestRule.onAllNodesWithText("Carregar mais", substring = true).assertCountEquals(0)
    }

    @Test
    fun page_error_keeps_rows_with_retry() {
        val calls = Calls()
        idleScreen(
            ready(error = HistoryError.Load(RepositoryFailure.Storage("down"))),
            calls
        )
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(4)
        composeTestRule.onAllNodesWithText("Série 1: 10 reps • 60.0 kg", substring = true).assertCountEquals(2)
        composeTestRule.onNodeWithText("Não foi possível carregar mais itens.").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.retried) }
    }

    @Test
    fun stats_warning_keeps_table() {
        idleScreen(ready(statsData = null, error = HistoryError.Stats(RepositoryFailure.Storage("down"))))
        composeTestRule.onNodeWithText("Não foi possível carregar o resumo").assertExists()
        // Stats warning shifts rows one index down.
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(5)
        composeTestRule.onAllNodesWithText("Série 1: 10 reps • 60.0 kg", substring = true).assertCountEquals(2)
    }

    @Test
    fun chart_shows_axis_dates_and_secondary_metric_band() {
        // T9.2 (B-3 residual): the axis date labels and the secondary metric
        // band were implemented without any assertion.
        val chart = chart().copy(
            unitSecondary = "s",
            points = listOf(
                com.offlineworkout.data.model.HistoryPoint(1000L, 1L, 1, 50.0, 55.0, 10),
                com.offlineworkout.data.model.HistoryPoint(2000L, 1L, 1, 60.0, 65.0, 10)
            )
        )
        idleScreen(ready(chartData = chart))
        // Date labels on the X axis: the chart prints the first and last point
        // dates, and the summary repeats the first in its own wording.
        // Both ends of the range carry a date; the same dates also appear in the
        // table rows, so only presence is asserted here.
        composeTestRule.onAllNodesWithText(formatHistoryDate(1000L))[0].assertExists()
        composeTestRule.onAllNodesWithText(formatHistoryDate(2000L))[0].assertExists()
        // Secondary metric band: its own legend row carrying the secondary
        // scale, plus the primary axis caption.
        // The scales are padded by scaleOf, so the assertion locks the labels
        // and the unit, not the exact padded numbers.
        composeTestRule.onNodeWithText("Duração (s): ", substring = true).assertExists()
        composeTestRule.onNodeWithText("Eixo: ", substring = true).assertExists()
        // The primary legend keeps the unit, and the caption ends with it too.
        composeTestRule.onAllNodesWithText(" kg", substring = true)[0].assertExists()
    }

    @Test
    fun load_more_is_hidden_on_page_error_even_with_more_available() {
        // T9.2 (B-3 residual): the pager must not offer another page when the
        // page that failed is the one the user would retry.
        idleScreen(ready(hasMore = true, error = HistoryError.Load(RepositoryFailure.Storage("down"))))
        // The retry banner replaces the pager: offering "Carregar mais" here
        // would let the user ask for a page that already failed.
        composeTestRule.onAllNodesWithText("Carregar mais", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(4)
        composeTestRule.onNodeWithText("Não foi possível carregar mais itens.").assertExists()
        composeTestRule.onAllNodesWithText("Carregar mais", substring = true).assertCountEquals(0)
    }

    @Test
    fun summary_is_derived_from_rows_when_stats_are_missing() {
        // T9.2 (B-3 residual): without stats the summary still has to show the
        // first date and the last metric, read from the rows themselves.
        idleScreen(ready(statsData = null, error = HistoryError.Stats(RepositoryFailure.Storage("down"))))
        composeTestRule.onNodeWithText(
            "2 execuções • primeira ${formatHistoryTimestamp(1000L)}",
            substring = true
        ).assertExists()
        composeTestRule.onNodeWithText("último peso: 60.0 kg", substring = true).assertExists()
    }

    @Test
    fun empty_history_message() {
        idleScreen(ready(rows = emptyList(), chartData = null, statsData = HistoryStats(1L, 0, null, null)))
        composeTestRule.onNodeWithText("Sem execuções para este exercício").assertExists()
    }

    @Test
    fun summary_tiles_show_sessions_and_last_load() {
        idleScreen(ready())
        composeTestRule.onNodeWithText("Sessões").assertExists()
        composeTestRule.onNodeWithText("2").assertExists()
        composeTestRule.onNodeWithText("Última carga").assertExists()
        composeTestRule.onNodeWithText("60.0").assertExists()
        composeTestRule.onNodeWithText("kg").assertExists()
    }

    @Test
    fun selector_collapses_to_plus_n_beyond_four() {
        val calls = Calls()
        val six = (1L..6L).map { seriesSpec(it, "Ex$it") }
        idleScreen(
            ready(exercises = six, selected = null, rows = emptyList(), chartData = null, statsData = null),
            calls
        )
        // Colapsado: 4 primeiros + Todos + "+2"; o quinto fica oculto.
        // A fileira é lazy: rola até o "+2" para compô-lo antes de tocar.
        composeTestRule.onNodeWithText("Ex4").assertExists()
        composeTestRule.onNodeWithTag("exerciseChips").performScrollToIndex(5)
        composeTestRule.onNodeWithText("+2").assertExists()
        composeTestRule.onAllNodesWithText("Ex5").assertCountEquals(0)
        // Expandir revela todos; tocar seleciona.
        composeTestRule.onNodeWithText("+2").performClick()
        composeTestRule.onNodeWithText("Ex5").assertExists()
        composeTestRule.onNodeWithTag("exerciseChips").performScrollToIndex(7)
        composeTestRule.onNodeWithText("Mostrar menos").assertExists()
        composeTestRule.onNodeWithTag("exerciseChips").performScrollToIndex(6)
        composeTestRule.onNodeWithContentDescription("Ver histórico de Ex6").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(6L), calls.selected) }
        // Recolher volta ao estado inicial.
        composeTestRule.onNodeWithTag("exerciseChips").performScrollToIndex(7)
        composeTestRule.onNodeWithText("Mostrar menos").performClick()
        composeTestRule.onAllNodesWithText("Ex5").assertCountEquals(0)
    }

    @Test
    fun selecting_all_chip_clears_selection() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithText("Todos").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.cleared) }
    }

    @Test
    fun rows_group_by_day_with_headers() {
        // Dois dias distintos: 1_700_000_000_000L e +1 dia. A lista é lazy,
        // então cada cabeçalho é provado enquanto está na dobra: o texto de
        // data exata só existe no DayHeader (as linhas trazem data+hora).
        val day1 = 1_700_000_000_000L
        val day2 = day1 + 86_400_000L
        idleScreen(
            ready(
                rows = listOf(execution(11L, day2), execution(10L, day1)),
                chartData = null
            )
        )
        composeTestRule.onNodeWithText(formatHistoryDate(day2)).assertExists()
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(6)
        composeTestRule.onNodeWithText(formatHistoryDate(day1)).assertExists()
    }
}
