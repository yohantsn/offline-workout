package com.offlineworkout.ui.execution

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestTimerSnapshot
import com.offlineworkout.data.timer.RestTimerStatus
import com.offlineworkout.viewmodel.ExecutionError
import com.offlineworkout.viewmodel.ExecutionExerciseState
import com.offlineworkout.viewmodel.SESSION_RESTORED_NOTICE_KEY
import com.offlineworkout.viewmodel.SeriesSetInputState
import com.offlineworkout.viewmodel.WorkoutExecutionUiState
import com.offlineworkout.data.model.WorkoutSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T6.1: execution screen renders the immutable UiState: header/total, session
// bar, rest banner, notices/errors, exercise list with three types, series
// rows, pager and finish flow. Stateless, no Room/Service.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutExecutionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun seriesSpec(id: Long, name: String, position: Int, rest: Int = 60) = ExerciseSpec(
        id = id, workoutId = 7L, name = name, type = ExerciseType.SERIES,
        position = position, restSeconds = rest, targetSets = 2, targetReps = 10,
        targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun timedSpec(id: Long) = ExerciseSpec(
        id = id, workoutId = 7L, name = "Plank", type = ExerciseType.TIMED,
        position = 1, restSeconds = 30, targetSets = null, targetReps = null,
        targetWeightKg = null, targetDurationSeconds = 60L, targetDistanceMeters = null
    )

    private fun distanceSpec(id: Long) = ExerciseSpec(
        id = id, workoutId = 7L, name = "Run", type = ExerciseType.DISTANCE_TIME,
        position = 2, restSeconds = 0, targetSets = null, targetReps = null,
        targetWeightKg = null, targetDurationSeconds = 600L, targetDistanceMeters = 1000.0
    )

    private fun exerciseState(spec: ExerciseSpec) = ExecutionExerciseState(
        spec = spec,
        seriesSets = if (spec.type == ExerciseType.SERIES) {
            listOf(
                SeriesSetInputState(1, "10", "", null),
                SeriesSetInputState(2, "7", "", null)
            )
        } else {
            emptyList()
        },
        durationSecondsText = "",
        distanceMetersText = "",
        isCompleted = false,
        completedAtWallClockMs = null,
        latestExecution = null,
        latestSet = null
    )

    private fun session(
        status: WorkoutSessionStatus = WorkoutSessionStatus.Running(7L),
        elapsed: Long = 65L
    ) = WorkoutSessionState(
        status = status,
        workoutId = 7L,
        elapsedSeconds = elapsed,
        startedAtWallClockMs = 1L,
        accumulatedMs = elapsed * 1000,
        lastResumedAtWallClockMs = 1L
    )

    private fun ready(
        exercises: List<ExecutionExerciseState> = listOf(
            exerciseState(seriesSpec(1L, "Squat", 0)),
            exerciseState(timedSpec(2L)),
            exerciseState(distanceSpec(3L))
        ),
        selected: Long? = 1L,
        timer: RestTimerSnapshot? = null,
        sessionState: WorkoutSessionState = session(),
        saving: Long? = null,
        error: ExecutionError? = null,
        notice: String? = null,
        workout: WorkoutSummary = WorkoutSummary(7L, "A", "Alpha", 1L)
    ) = WorkoutExecutionUiState.Ready(
        workout = workout,
        exercises = exercises,
        selectedExerciseId = selected,
        restTimer = timer,
        session = sessionState,
        savingExerciseId = saving,
        error = error,
        notice = notice
    )

    private fun runningSnapshot(exerciseId: Long = 1L, remaining: Int = 45) = RestTimerSnapshot(
        status = RestTimerStatus.Running("rest-7-1-1000"),
        workoutId = 7L, exerciseId = exerciseId, durationSeconds = 60,
        remainingSeconds = remaining, startedAtWallClockMs = 1000L,
        endsAtWallClockMs = 61_000L, alertDelivered = false
    )

    private class Calls {
        val selected = mutableListOf<Long>()
        val completedSets = mutableListOf<Pair<Long, Int>>()
        val completedExercises = mutableListOf<Long>()
        val started = mutableListOf<Long>()
        var cancels = 0
        var historyOpened = 0
        var paused = 0
        var resumed = 0
        var finished = 0
        var retries = 0
        var noticed = 0
        var backed = 0
        val repsInputs = mutableListOf<String>()
        val durationInputs = mutableListOf<String>()
        val distanceInputs = mutableListOf<String>()
    }

    private fun idleScreen(
        state: WorkoutExecutionUiState,
        calls: Calls = Calls(),
        fontScale: Float = 1f
    ) {
        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = composeTestRule.density.density,
                    fontScale = fontScale
                )
            ) {
                WorkoutExecutionScreen(
                    state = state,
                    onSelectExercise = { calls.selected.add(it) },
                    onNextExercise = { calls.selected.add(-1L) },
                    onPreviousExercise = { calls.selected.add(-2L) },
                    onRepsChanged = { _, _, value -> calls.repsInputs.add(value) },
                    onWeightChanged = { _, _, _ -> },
                    onDurationChanged = { _, value -> calls.durationInputs.add(value) },
                    onDistanceChanged = { _, value -> calls.distanceInputs.add(value) },
                    onCompleteSeriesSet = { id, set -> calls.completedSets.add(id to set) },
                    onCompleteExercise = { calls.completedExercises.add(it) },
                    onStartRest = { calls.started.add(it) },
                    onCancelRest = { calls.cancels += 1 },
                    onHistorySelected = { calls.historyOpened += 1 },
                    onPauseSession = { calls.paused += 1 },
                    onResumeSession = { calls.resumed += 1 },
                    onFinishWorkout = { calls.finished += 1 },
                    onRetry = { calls.retries += 1 },
                    onNoticeShown = { calls.noticed += 1 },
                    onBack = { calls.backed += 1 }
                )
            }
        }
    }

    @Test
    fun loading_state() {
        idleScreen(WorkoutExecutionUiState.Loading)
        composeTestRule.onNodeWithText("Carregando treino…").assertExists()
    }

    @Test
    fun notfound_state() {
        idleScreen(WorkoutExecutionUiState.NotFound)
        composeTestRule.onNodeWithText("Treino não encontrado.").assertExists()
        composeTestRule.onNodeWithText("Voltar para treinos").assertExists()
    }

    @Test
    fun load_error_state() {
        idleScreen(WorkoutExecutionUiState.Error("Falha ao carregar"))
        composeTestRule.onNodeWithText("Falha ao carregar").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").assertExists()
    }

    @Test
    fun header_shows_name_total_and_session_actions() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithText("Alpha").assertExists()
        composeTestRule.onNodeWithText("Tempo total: 01:05").assertExists()
        composeTestRule.onNodeWithText("Pausar").performClick()
        composeTestRule.onNodeWithText("Finalizar treino").assertExists()
        composeTestRule.runOnIdle { assertEquals(1, calls.paused) }
    }

    @Test
    fun paused_session_offers_resume() {
        idleScreen(ready(sessionState = session(WorkoutSessionStatus.Paused(7L), 30L)))
        composeTestRule.onNodeWithText("Retomar").assertExists()
        composeTestRule.onNodeWithText("Tempo total: 00:30").assertExists()
    }

    @Test
    fun foreign_session_banner_is_shown_exactly_once() {
        // T9.2 (R-10): the sentence used to come from both the header and a
        // ViewModel notice, so it was rendered twice.
        idleScreen(
            ready(
                sessionState = WorkoutSessionState(
                    status = WorkoutSessionStatus.Running(9L),
                    workoutId = 9L,
                    elapsedSeconds = 10L,
                    startedAtWallClockMs = 1L,
                    accumulatedMs = 10_000L,
                    lastResumedAtWallClockMs = 1L
                )
            )
        )
        composeTestRule.onAllNodesWithText("Sessão ativa em outro treino").assertCountEquals(1)
    }

    @Test
    fun foreign_paused_session_disables_resume() {
        val foreign = WorkoutSessionState(
            status = WorkoutSessionStatus.Paused(9L),
            workoutId = 9L,
            elapsedSeconds = 10L,
            startedAtWallClockMs = 1L,
            accumulatedMs = 10_000L,
            lastResumedAtWallClockMs = 1L
        )
        idleScreen(ready(sessionState = foreign))
        composeTestRule.onNodeWithText("Retomar").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Finalizar treino").assertIsNotEnabled()
    }

    @Test
    fun other_workout_session_banner() {
        idleScreen(
            ready(
                sessionState = WorkoutSessionState(
                    status = WorkoutSessionStatus.Running(9L),
                    workoutId = 9L,
                    elapsedSeconds = 10L,
                    startedAtWallClockMs = 1L,
                    accumulatedMs = 10_000L,
                    lastResumedAtWallClockMs = 1L
                )
            )
        )
        composeTestRule.onNodeWithText("Sessão ativa em outro treino").assertExists()
    }

    @Test
    fun foreign_session_disables_session_actions() {
        val calls = Calls()
        idleScreen(
            ready(
                sessionState = WorkoutSessionState(
                    status = WorkoutSessionStatus.Running(9L),
                    workoutId = 9L,
                    elapsedSeconds = 10L,
                    startedAtWallClockMs = 1L,
                    accumulatedMs = 10_000L,
                    lastResumedAtWallClockMs = 1L
                )
            ),
            calls
        )
        composeTestRule.onNodeWithText("Sessão ativa em outro treino").assertExists()
        composeTestRule.onNodeWithText("Tempo total (outro treino): 00:10").assertExists()
        // Disabled actions swallow clicks: nothing is dispatched.
        composeTestRule.onNodeWithText("Pausar").performClick()
        composeTestRule.onNodeWithText("Finalizar treino").performClick()
        composeTestRule.runOnIdle {
            assertEquals(0, calls.paused)
            assertEquals(0, calls.finished)
        }
    }

    @Test
    fun series_type_renders_sets_fields_rest_and_cta() {
        idleScreen(ready())
        // Título + chip da strip: o nome aparece nos dois.
        composeTestRule.onAllNodesWithText("Squat").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Repetições (reps)", substring = true).assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Peso (kg, opcional)", substring = true).assertCountEquals(2)
        composeTestRule.onNodeWithText("Descanso: 60 s", substring = true).assertExists()
        composeTestRule.onNodeWithText("Concluir série 1").assertExists()
        composeTestRule.onNodeWithContentDescription("Exercício 1 de 3").assertExists()
    }

    @Test
    fun timed_type_renders_only_duration() {
        idleScreen(ready(selected = 2L))
        composeTestRule.onNodeWithText("Duração realizada (s)").assertExists()
        composeTestRule.onAllNodesWithText("Repetições (reps)", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithText("Concluir exercício").assertExists()
    }

    @Test
    fun distance_type_renders_distance_duration_and_no_rest() {
        idleScreen(ready(selected = 3L))
        composeTestRule.onNodeWithText("Distância (m)").assertExists()
        composeTestRule.onNodeWithText("Duração (s)").assertExists()
        composeTestRule.onNodeWithText("Sem descanso").assertExists()
    }

    @Test
    fun timed_complete_exercise_is_primary_64dp() {
        // Não-verificado do reviewer: em não-séries o CTA é "Concluir
        // exercício" em primário (64 dp provam o peso, não só a existência).
        val calls = Calls()
        idleScreen(ready(selected = 2L), calls)
        val cta = composeTestRule.onNodeWithText("Concluir exercício")
        cta.assertExists()
        val density = composeTestRule.density
        val heightDp = with(density) { cta.fetchSemanticsNode().size.height.toDp() }
        assertTrue("timed CTA height was $heightDp dp", heightDp >= 64.dp)
        cta.performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(2L), calls.completedExercises) }
    }

    @Test
    fun long_title_does_not_overlap_back_action() {
        // E-3: com 4 elementos a barra quebrava a 2.0×; com 3 (total no
        // card), título longo degrada por ellipsis sem colidir. Sem escala
        // de fonte no headless, 200 chars forçam a mesma pressão.
        val title = "T".repeat(200)
        idleScreen(ready(workout = WorkoutSummary(7L, "A", title, 1L)))
        val titleBounds = composeTestRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val backBounds = composeTestRule.onNodeWithText("Voltar").fetchSemanticsNode().boundsInRoot
        val overlaps = titleBounds.left < backBounds.right &&
            titleBounds.right > backBounds.left &&
            titleBounds.top < backBounds.bottom &&
            titleBounds.bottom > backBounds.top
        assertTrue("title $titleBounds overlaps back $backBounds", !overlaps)
    }

    @Test
    fun restored_notice_key_resolves_to_text() {
        // C-2: o VM emite chave; a UI resolve para strings.xml.
        idleScreen(ready(notice = SESSION_RESTORED_NOTICE_KEY))
        composeTestRule.onNodeWithText("Sessão anterior retomada").assertExists()
    }

    @Test
    fun session_actions_stack_vertically_at_large_font_scale() {
        // R-1: a 2.0× os botões empilham (lado a lado o "Descanso" virava
        // "De…"). Empilhado = Descanso começa onde Concluir termina.
        idleScreen(ready(), fontScale = 2f)
        val concluir = composeTestRule.onNodeWithText("Concluir exercício")
            .fetchSemanticsNode().boundsInRoot
        val descanso = composeTestRule.onNodeWithContentDescription("Iniciar descanso de Squat")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "buttons overlap vertically: concluir $concluir descanso $descanso",
            descanso.top >= concluir.bottom
        )
    }

    @Test
    fun strip_selects_exercise() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithText("Plank").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(2L), calls.selected) }
    }

    @Test
    fun series_completion_dispatches_callback() {
        val calls = Calls()
        idleScreen(ready(), calls)
        // CTA fixo no rodapé: sem rolagem até ele.
        composeTestRule.onNodeWithContentDescription("Concluir série 1 de Squat").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Concluir série 1 de Squat").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(1L to 1), calls.completedSets) }
    }

    @Test
    fun completed_series_renders_summary() {
        val done = exerciseState(seriesSpec(1L, "Squat", 0)).copy(
            isCompleted = true,
            seriesSets = listOf(SeriesSetInputState(1, "10", "60", 123L))
        )
        idleScreen(ready(exercises = listOf(done)))
        // Modo performance: série concluída colapsa para uma linha.
        composeTestRule.onNodeWithText("✓ 10 reps · 60 kg").assertExists()
        composeTestRule.onNodeWithText("Concluído").assertExists()
    }

    @Test
    fun reps_input_dispatches() {
        val calls = Calls()
        idleScreen(ready(), calls)
        // Valor único no campo ("7" só existe nas reps da série 2): sem
        // índice posicional frágil — se a ordem dos campos mudar, o teste
        // falha por não achar, nunca digitando no campo errado.
        composeTestRule.onNodeWithText("7").performTextReplacement("12")
        composeTestRule.runOnIdle { assertTrue(calls.repsInputs.contains("12")) }
    }

    @Test
    fun pager_selects_exercises() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithTag("executionList").performScrollToIndex(2)
        composeTestRule.onNodeWithText("Próximo").performClick()
        composeTestRule.onNodeWithText("Anterior").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(-1L, -2L), calls.selected) }
    }

    @Test
    fun running_rest_banner_with_cancel() {
        val calls = Calls()
        idleScreen(ready(timer = runningSnapshot()), calls)
        composeTestRule.onNodeWithText("0:45").assertExists()
        composeTestRule.onNodeWithText("Cancelar").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.cancels) }
    }

    @Test
    fun running_rest_replaces_complete_cta() {
        idleScreen(ready(timer = runningSnapshot()))
        // Durante o descanso, o descanso É a ação (R-23 por eliminação).
        composeTestRule.onAllNodesWithText("Concluir série", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Concluir exercício").assertCountEquals(0)
        composeTestRule.onNodeWithText("0:45").assertExists()
    }

    @Test
    fun current_exercise_opens_history() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithTag("executionList").performScrollToIndex(1)
        composeTestRule.onNodeWithContentDescription("Ver histórico de Squat").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.historyOpened) }
    }

    @Test
    fun finished_rest_banner() {
        idleScreen(
            ready(
                timer = RestTimerSnapshot(
                    status = RestTimerStatus.Finished("rest-7-1-1000"),
                    workoutId = 7L, exerciseId = 1L, durationSeconds = 60,
                    remainingSeconds = 0, startedAtWallClockMs = 1000L,
                    endsAtWallClockMs = 61_000L, alertDelivered = true
                )
            )
        )
        composeTestRule.onNodeWithText("Descanso concluído").assertExists()
    }

    @Test
    fun notice_is_announced_and_survives_more_than_one_frame() {
        // T9.3 (bloqueio 4): the notice used to be acknowledged in the same
        // frame it appeared, so the polite live region never got a chance to be
        // read. The screen must acknowledge it only after a delay.
        composeTestRule.mainClock.autoAdvance = false
        val noticed = IntArray(1)
        composeTestRule.setContent {
            WorkoutExecutionScreen(
                state = ready(notice = "Série não encontrada. Revise as séries."),
                onSelectExercise = {},
                onNextExercise = {},
                onPreviousExercise = {},
                onRepsChanged = { _, _, _ -> },
                onWeightChanged = { _, _, _ -> },
                onDurationChanged = { _, _ -> },
                onDistanceChanged = { _, _ -> },
                onCompleteSeriesSet = { _, _ -> },
                onCompleteExercise = {},
                onStartRest = {},
                onCancelRest = {},
                onHistorySelected = {},
                onPauseSession = {},
                onResumeSession = {},
                onFinishWorkout = {},
                onRetry = {},
                onNoticeShown = { noticed[0] += 1 },
                onBack = {}
            )
        }
        composeTestRule.onNodeWithText("Série não encontrada. Revise as séries.").assertExists()
        composeTestRule.runOnIdle {
            assertEquals("the notice must not be dismissed on the first frame", 0, noticed[0])
        }
        composeTestRule.mainClock.advanceTimeBy(NOTICE_VISIBLE_MS + 100)
        composeTestRule.runOnIdle {
            assertEquals("the notice is dismissed after the announcement window", 1, noticed[0])
        }
    }

    @Test
    fun save_error_with_retry() {
        val calls = Calls()
        idleScreen(
            ready(error = ExecutionError.Save(1L, RepositoryFailure.Storage("down"))),
            calls
        )
        composeTestRule.onNodeWithText("Não foi possível salvar. Verifique os campos.").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(1L), calls.completedExercises) }
    }

    @Test
    fun latest_error_warning_is_announced_politely() {
        // T9.2 (B-2 residual, Fase 6): the latest-history warning is
        // informational, not blocking: it must live in a polite live region so
        // it cannot regress into the assertive block used for real errors.
        idleScreen(ready(error = ExecutionError.Latest(1L, RepositoryFailure.Storage("down"))))
        composeTestRule.onNodeWithText("Não foi possível ler o histórico anterior.").assertExists()
        val politeLiveRegion = SemanticsMatcher("live region is polite") { node ->
            var polite = false
            for ((key, value) in node.config) {
                if (key == SemanticsProperties.LiveRegion) polite = value == LiveRegionMode.Polite
            }
            polite
        }
        // The live region lives on the banner, the text on its child.
        composeTestRule.onNode(
            hasText("Não foi possível ler o histórico anterior.", substring = true) and
                hasAnyAncestor(politeLiveRegion)
        ).assertExists()
    }

    @Test
    fun finish_flow_confirms_then_calls_back() {
        val calls = Calls()
        idleScreen(ready(), calls)
        composeTestRule.onNodeWithText("Finalizar treino").performClick()
        composeTestRule.onNodeWithText("Finalizar").performClick()
        composeTestRule.runOnIdle { assertEquals(1, calls.finished) }
    }

    @Test
    fun empty_workout_message() {
        idleScreen(ready(exercises = emptyList()))
        composeTestRule.onNodeWithText("Treino sem exercícios").assertExists()
    }

    @Test
    fun completed_marker_is_announced_assertively() {
        // T9.3 (bloqueio 5): T6.3 A-4 approved the assertive announcement and
        // the rebuild had dropped the semantics entirely.
        val done = exerciseState(seriesSpec(1L, "Squat", 0)).copy(isCompleted = true)
        idleScreen(ready(exercises = listOf(done)))
        val assertive = SemanticsMatcher("assertive live region") { node ->
            var value = false
            for ((key, config) in node.config) {
                if (key == SemanticsProperties.LiveRegion) {
                    value = config == LiveRegionMode.Assertive
                }
            }
            value
        }
        composeTestRule.onNodeWithText("Concluído").assertExists()
        composeTestRule.onNode(
            hasText("Concluído") and (SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) or assertive)
        ).assertExists()
    }

    @Test
    fun latest_read_failure_has_no_write_retry() {
        // T9.3 (bloqueio 3): the retry used to call onCompleteExercise for a
        // *read* failure, which wrote a history execution. The screen must not
        // offer that action at all.
        val calls = Calls()
        idleScreen(ready(error = ExecutionError.Latest(1L, RepositoryFailure.Storage("down"))), calls)
        composeTestRule.onAllNodesWithText("Tentar novamente").assertCountEquals(0)
        composeTestRule.onNodeWithText("Não foi possível ler o histórico anterior.").assertExists()
        composeTestRule.runOnIdle {
            assertEquals("a read failure must never trigger a write", 0, calls.completedExercises.size)
        }
    }

    @Test
    fun save_failure_still_retries_the_write() {
        val calls = Calls()
        idleScreen(ready(error = ExecutionError.Save(1L, RepositoryFailure.Storage("down"))), calls)
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(1L), calls.completedExercises) }
    }

    @Test
    fun foreign_session_disables_all_three_session_actions() {
        // T9.3 (bloqueio 6): the old test passed because the click opened the
        // finish dialog, not because the button was disabled. The contract is
        // the state of the buttons.
        val foreign = WorkoutSessionState(
            status = WorkoutSessionStatus.Running(9L),
            workoutId = 9L,
            elapsedSeconds = 10L,
            startedAtWallClockMs = 1L,
            accumulatedMs = 10_000L,
            lastResumedAtWallClockMs = 1L
        )
        idleScreen(ready(sessionState = foreign))
        composeTestRule.onNodeWithText("Pausar").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Finalizar treino").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Finalizar").assertCountEquals(0)
    }

    @Test
    fun completed_and_current_indicators() {
        val done = exerciseState(seriesSpec(1L, "Squat", 0)).copy(isCompleted = true)
        idleScreen(ready(exercises = listOf(done, exerciseState(timedSpec(2L))), selected = 2L))
        // Concluído não atual: chip da strip com ✓ + dot de sucesso.
        composeTestRule.onNodeWithText("Squat ✓").assertExists()
        composeTestRule.onNodeWithContentDescription("Exercício atual: Plank").assertExists()
    }
}
