package com.offlineworkout.viewmodel

import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestRequest
import com.offlineworkout.data.timer.RestTimerCommandResult
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerSnapshot
import com.offlineworkout.data.timer.RestTimerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T6.1: single execution ViewModel. Fake repositories/timer/session, no Room,
// no Service, no real clock reads asserted (only ordering/positivity).
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutExecutionViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    private fun seriesSpec(id: Long, name: String, position: Int, rest: Int = 60) = ExerciseSpec(
        id = id, workoutId = 7L, name = name, type = ExerciseType.SERIES,
        position = position, restSeconds = rest, targetSets = 2, targetReps = 10,
        targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun ready(vm: WorkoutExecutionViewModel): WorkoutExecutionUiState.Ready {
        val state = vm.state.value
        assertTrue("expected Ready, got $state", state is WorkoutExecutionUiState.Ready)
        return state as WorkoutExecutionUiState.Ready
    }

    private suspend fun TestScope.awaitReady(vm: WorkoutExecutionViewModel): WorkoutExecutionUiState.Ready {
        return vm.state.first { it is WorkoutExecutionUiState.Ready } as WorkoutExecutionUiState.Ready
    }

    @Test
    fun restored_session_announces_resume_once() = runTest {
        // E-6: sessão pré-existente NESTE treino com tempo acumulado foi
        // restaurada — anuncia uma vez, sem mudar o cronômetro.
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val session = FakeSession(
            WorkoutSessionState(
                status = WorkoutSessionStatus.Running(7L),
                workoutId = 7L,
                elapsedSeconds = 4000L,
                startedAtWallClockMs = 1L,
                accumulatedMs = 4_000_000L,
                lastResumedAtWallClockMs = 1L
            )
        )
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), session)
        assertEquals(SESSION_RESTORED_NOTICE_KEY, awaitReady(vm).notice)
    }

    @Test
    fun fresh_session_announces_nothing() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val vm = WorkoutExecutionViewModel(
            7L, repo, FakeHistory(), FakeTimer(),
            FakeSession(
                WorkoutSessionState(
                    status = WorkoutSessionStatus.Idle,
                    workoutId = null,
                    elapsedSeconds = 0L,
                    startedAtWallClockMs = 0L,
                    accumulatedMs = 0L,
                    lastResumedAtWallClockMs = 0L
                )
            )
        )
        assertNull(awaitReady(vm).notice)
    }

    @Test
    fun timer_ticks_keep_the_exercise_entries_identical() = runTest {
        // T9.2 (R-15b): the rest engine writes the remaining seconds once a
        // second for the whole rest period. If the timer tick produced new
        // ExecutionExerciseState instances, every visible ExerciseCard — and its
        // OutlinedTextFields — would recompose 1x/s. Identity is what Compose
        // consumes to skip a recomposition, so the entries must be the very same
        // objects while only the timer snapshot changes.
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), timer, FakeSession())
        val first = awaitReady(vm)

        // Ten ticks, exactly like ten seconds of rest.
        repeat(10) { tick ->
            timer.stateFlow.value = RestTimerSnapshot(
                status = RestTimerStatus.Running("rest-7-1-$tick"),
                workoutId = 7L,
                exerciseId = 1L,
                durationSeconds = 60,
                remainingSeconds = 60 - tick,
                startedAtWallClockMs = 1L,
                endsAtWallClockMs = 60_000L - tick * 1000L,
                alertDelivered = false
            )
            runCurrent()
        }
        val last = ready(vm)

        assertSame(
            "the exercise list itself must be reused across timer ticks",
            first.exercises,
            last.exercises
        )
        first.exercises.indices.forEach { index ->
            assertSame(
                "exercise entry $index must keep its identity across timer ticks",
                first.exercises[index],
                last.exercises[index]
            )
        }
        // The timer did advance, so the reuse is not a frozen state.
        assertEquals(51, (last.restTimer?.remainingSeconds))
        // And the user-visible values are unchanged.
        assertEquals(first.exercises.map { it.seriesSets }, last.exercises.map { it.seriesSets })
    }

    @Test
    fun typed_input_still_produces_a_new_entry() = runTest {
        // T9.2 (R-15b): identity must be reused only when nothing behind it
        // changed — a keystroke has to produce a new entry, or the text field
        // would never update.
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), timer, FakeSession())
        val before = awaitReady(vm)

        vm.onRepsChanged(1L, 1, "12")
        runCurrent()
        val after = ready(vm)

        assertNotSame("a keystroke must build a new entry", before.exercises[0], after.exercises[0])
        assertEquals("12", after.exercises[0].seriesSets.first().repsText)
    }

    @Test
    fun selection_change_keeps_entries_and_moves_the_selection() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), FakeSession())
        val before = awaitReady(vm)

        vm.onSelectExercise(2L)
        runCurrent()
        val after = ready(vm)

        assertEquals(2L, after.selectedExerciseId)
        // Selecting loads the newly selected exercise's latest prefill, so that
        // entry may legitimately change; the untouched one must not.
        assertSame(
            "an exercise unrelated to the selection keeps its identity",
            before.exercises[0],
            after.exercises[0]
        )
    }

    @Test
    fun loads_workout_and_selects_first() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), FakeSession())
        val ready = awaitReady(vm)
        assertEquals("Alpha", ready.workout.name)
        assertEquals(listOf(1L, 2L), ready.exercises.map { it.spec.id })
        assertEquals(1L, ready.selectedExerciseId)
    }

    @Test
    fun missing_workout_is_not_found_without_writes() = runTest {
        setTestMain()
        val history = FakeHistory()
        val vm = WorkoutExecutionViewModel(7L, FakeWorkoutRepository(), history, FakeTimer(), FakeSession())
        assertTrue(vm.state.first { it is WorkoutExecutionUiState.NotFound } is WorkoutExecutionUiState.NotFound)
        assertTrue(history.writes.isEmpty())
    }

    @Test
    fun next_previous_respect_limits() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), FakeSession())
        awaitReady(vm)
        vm.onPreviousExercise()
        assertEquals(1L, ready(vm).selectedExerciseId)
        vm.onNextExercise()
        assertEquals(2L, ready(vm).selectedExerciseId)
        vm.onNextExercise()
        assertEquals(2L, ready(vm).selectedExerciseId)
        vm.onSelectExercise(1L)
        assertEquals(1L, ready(vm).selectedExerciseId)
    }

    @Test
    fun latest_prefills_weight_and_reps() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistory()
        history.latestSetValue(1L, ExecutionSetView(setNumber = 2, reps = 8, weightKg = 62.5))
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        val ready = awaitReady(vm)
        runCurrent()
        val row = ready(vm).exercises.single().seriesSets.first()
        assertEquals("62.5", row.weightKgText)
        assertEquals("8", row.repsText)
        assertTrue(ready.exercises.single().seriesSets.isNotEmpty())
    }

    @Test
    fun no_latest_leaves_weight_blank_and_reps_from_target() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        val row = ready(vm).exercises.single().seriesSets.first()
        assertEquals("", row.weightKgText)
        assertEquals("10", row.repsText)
    }

    @Test
    fun latest_error_is_non_blocking() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistory()
        history.latestError = RepositoryException(RepositoryFailure.Storage("down"))
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        val ready = awaitReady(vm)
        runCurrent()
        val withError = ready(vm)
        assertTrue(withError.error is ExecutionError.Latest)
        // Form stays editable: typing still works.
        vm.onRepsChanged(1L, 1, "9")
        assertEquals("9", ready(vm).exercises.single().seriesSets.first().repsText)
        assertTrue(ready.error is ExecutionError.Latest)
    }

    @Test
    fun complete_series_marks_locally_starts_rest_no_history_write() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0, rest = 90))
        val history = FakeHistory()
        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(7L, repo, history, timer, FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onRepsChanged(1L, 1, "10")
        vm.onCompleteSeriesSet(1L, 1)
        val row = ready(vm).exercises.single().seriesSets.first()
        assertTrue(row.completedAtWallClockMs != null)
        assertEquals(listOf(RestRequest(7L, 1L, 90)), timer.starts)
        assertTrue(history.writes.isEmpty())
        assertTrue(ready(vm).exercises.single().isCompleted == false)
    }

    @Test
    fun complete_series_validates_and_busy_warns_without_revert() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val timer = FakeTimer(nextStart = RestTimerCommandResult.Busy)
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), timer, FakeSession())
        awaitReady(vm)
        runCurrent()
        // Row 2 blanked: validation fails before any timer call.
        vm.onRepsChanged(1L, 2, "")
        vm.onCompleteSeriesSet(1L, 2)
        assertEquals("Repetições deve ser inteiro > 0", ready(vm).notice)
        assertTrue(timer.starts.isEmpty())

        vm.onRepsChanged(1L, 2, "10")
        vm.onCompleteSeriesSet(1L, 2)
        assertEquals("Já há um descanso em andamento", ready(vm).notice)
        assertEquals(1, timer.starts.size)
        assertTrue(ready(vm).exercises.single().seriesSets[1].completedAtWallClockMs != null)
    }

    @Test
    fun complete_exercise_writes_single_atomic_execution() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0, rest = 0))
        val history = FakeHistory(result = Result.success(55L))
        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(7L, repo, history, timer, FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onRepsChanged(1L, 1, "10")
        vm.onWeightChanged(1L, 1, "60")
        vm.onCompleteSeriesSet(1L, 1)
        vm.onRepsChanged(1L, 2, "8")
        vm.onCompleteSeriesSet(1L, 2)
        vm.onCompleteExercise(1L)
        runCurrent()

        assertEquals(1, history.writes.size)
        val input = history.writes.single() as SeriesExecutionInput
        assertEquals(1L, input.exerciseId)
        assertEquals(listOf(1, 2), input.sets.map { it.setNumber })
        assertEquals(listOf(10, 8), input.sets.map { it.reps })
        assertEquals(60.0, input.sets[0].weightKg)
        assertNull(input.sets[1].weightKg)
        val completedAt = input.completedAt
        assertTrue(completedAt != null && completedAt >= input.performedAt && input.performedAt > 0)
        val done = ready(vm).exercises.single()
        assertTrue(done.isCompleted)
        // restSeconds = 0: no timer started.
        assertTrue(timer.starts.isEmpty())
    }

    @Test
    fun save_failure_keeps_state_and_retries_once() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0, rest = 0))
        val history = FakeHistory(result = Result.failure(RepositoryFailure.Storage("down")))
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onRepsChanged(1L, 1, "10")
        vm.onCompleteSeriesSet(1L, 1)
        vm.onCompleteExercise(1L)
        runCurrent()
        val failed = ready(vm)
        assertTrue(failed.error is ExecutionError.Save)
        assertTrue(failed.exercises.single().isCompleted == false)

        history.result = Result.success(9L)
        vm.onCompleteExercise(1L)
        runCurrent()
        assertEquals(2, history.writes.size)
        assertTrue(ready(vm).exercises.single().isCompleted)
        assertNull(ready(vm).error)
    }

    @Test
    fun concurrent_saves_write_once() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0, rest = 0))
        val gate = CompletableDeferred<Result<Long>>()
        val history = FakeHistory(deferred = gate)
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onRepsChanged(1L, 1, "10")
        vm.onCompleteSeriesSet(1L, 1)
        vm.onCompleteExercise(1L)
        runCurrent()
        assertEquals(1L, ready(vm).savingExerciseId)
        vm.onCompleteExercise(1L)
        runCurrent()
        assertEquals(1, history.writes.size)
        gate.complete(Result.success(3L))
        runCurrent()
        assertEquals(1, history.writes.size)
        assertTrue(ready(vm).exercises.single().isCompleted)
    }

    @Test
    fun timed_and_distance_validate_and_write_once() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(
            ExerciseSpec(
                id = 1L, workoutId = 7L, name = "Plank", type = ExerciseType.TIMED,
                position = 0, restSeconds = 30, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 60L, targetDistanceMeters = null
            ),
            ExerciseSpec(
                id = 2L, workoutId = 7L, name = "Run", type = ExerciseType.DISTANCE_TIME,
                position = 1, restSeconds = 0, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 600L, targetDistanceMeters = 1000.0
            )
        )
        val history = FakeHistory(result = Result.success(1L))
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()

        // Duration comes prefilled from the target; blank it to force validation.
        vm.onDurationChanged(1L, "")
        vm.onCompleteExercise(1L)
        assertEquals("Duração deve ser inteiro > 0", ready(vm).notice)
        vm.onDurationChanged(1L, "60")
        vm.onCompleteExercise(1L)
        runCurrent()
        val timed = history.writes.single() as TimedExecutionInput
        assertEquals(60L, timed.durationSeconds)

        vm.onSelectExercise(2L)
        // Prefilled from targets; blank both to force validation step by step.
        vm.onDurationChanged(2L, "")
        vm.onDistanceChanged(2L, "")
        vm.onCompleteExercise(2L)
        assertEquals("Duração deve ser inteiro > 0", ready(vm).notice)
        vm.onDurationChanged(2L, "600")
        vm.onDistanceChanged(2L, "-5")
        vm.onCompleteExercise(2L)
        assertEquals("Distância deve ser decimal >= 0", ready(vm).notice)
        vm.onDistanceChanged(2L, "1000")
        vm.onCompleteExercise(2L)
        runCurrent()
        val distance = history.writes.last() as DistanceTimeExecutionInput
        assertEquals(600L, distance.durationSeconds)
        assertTrue(distance.distanceMeters == 1000.0)
        assertEquals(2, history.writes.size)
    }

    @Test
    fun finish_cancels_running_rest_stops_session_and_emits_once() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val timer = FakeTimer()
        timer.stateFlow.value = RestTimerSnapshot(
            status = RestTimerStatus.Running("rest-7-1-1"),
            workoutId = 7L, exerciseId = 1L, durationSeconds = 60, remainingSeconds = 30,
            startedAtWallClockMs = 1L, endsAtWallClockMs = 2L, alertDelivered = false
        )
        val session = FakeSession()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), timer, session)
        awaitReady(vm)
        vm.onFinishWorkout()
        assertEquals(listOf("rest-7-1-1"), timer.cancels)
        assertEquals(1, session.stops)
        assertEquals(WorkoutExecutionEffect.WorkoutFinished, vm.effects.first())
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
    }

    @Test
    fun session_pause_resume_delegate() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = emptyList()
        val session = FakeSession()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), session)
        awaitReady(vm)
        assertEquals(listOf(7L), session.started)
        assertTrue(ready(vm).exercises.isEmpty())
        vm.onPauseSession()
        vm.onResumeSession()
        assertEquals(1, session.pauses)
        assertEquals(1, session.resumes)
    }

    @Test
    fun foreign_session_actions_are_noops() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val session = FakeSession(
            initial = WorkoutSessionState(
                status = WorkoutSessionStatus.Running(9L),
                workoutId = 9L,
                elapsedSeconds = 10L,
                startedAtWallClockMs = 1L,
                accumulatedMs = 10_000L,
                lastResumedAtWallClockMs = 1L
            )
        )
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), session)
        awaitReady(vm)
        vm.onPauseSession()
        vm.onResumeSession()
        vm.onFinishWorkout()
        assertEquals(0, session.pauses)
        assertEquals(0, session.resumes)
        assertEquals(0, session.stops)
        // T9.3 (bloqueio 6) restored the refusal notice, and T9.2 (R-10) kept it
        // from being a copy of the header sentence: it says what was refused,
        // in different words.
        assertEquals(
            WorkoutExecutionViewModel.FOREIGN_SESSION_ACTION_REFUSED,
            ready(vm).notice
        )
        assertNotEquals(
            com.offlineworkout.ui.execution.FOREIGN_SESSION_NOTICE,
            ready(vm).notice
        )
        assertEquals(9L, ready(vm).session.workoutId)
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
    }

    @Test
    fun finishing_with_a_foreign_session_cancels_its_rest_and_tells_the_user() = runTest {
        // T9.3 (bloqueio 6): the rejection returned before cancelling the rest
        // timer, so the OTHER workout's countdown kept running, and after R-10
        // the refusal was completely silent.
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val session = FakeSession(
            initial = WorkoutSessionState(
                status = WorkoutSessionStatus.Running(9L),
                workoutId = 9L,
                elapsedSeconds = 10L,
                startedAtWallClockMs = 1L,
                accumulatedMs = 10_000L,
                lastResumedAtWallClockMs = 1L
            )
        )
        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), timer, session)
        awaitReady(vm)
        // A rest started by the other workout is on the clock.
        timer.stateFlow.value = RestTimerSnapshot(
            status = RestTimerStatus.Running("rest-9-2-1000"),
            workoutId = 9L,
            exerciseId = 2L,
            durationSeconds = 60,
            remainingSeconds = 45,
            startedAtWallClockMs = 1L,
            endsAtWallClockMs = 61_000L,
            alertDelivered = false
        )
        runCurrent()

        vm.onFinishWorkout()
        runCurrent()

        assertEquals(
            "the foreign rest timer must be cancelled",
            listOf("rest-9-2-1000"),
            timer.cancels
        )
        assertEquals("the foreign session must not be stopped", 0, session.stops)
        assertEquals(
            "the user must be told the action was refused",
            WorkoutExecutionViewModel.FOREIGN_SESSION_ACTION_REFUSED,
            ready(vm).notice
        )
        // And the notice is not a copy of the header sentence.
        assertNotEquals(
            com.offlineworkout.ui.execution.FOREIGN_SESSION_NOTICE,
            ready(vm).notice
        )
    }

    @Test
    fun finish_workout_emits_only_once() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = emptyList()
        val session = FakeSession()
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), session)
        awaitReady(vm)
        vm.onFinishWorkout()
        vm.onFinishWorkout()
        assertEquals(WorkoutExecutionEffect.WorkoutFinished, vm.effects.first())
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
        assertEquals(1, session.stops)
    }

    @Test
    fun history_selected_emits_open_history_for_current_exercise() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val vm = WorkoutExecutionViewModel(7L, repo, FakeHistory(), FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onHistorySelected()
        assertEquals(WorkoutExecutionEffect.OpenHistory(7L, 1L), vm.effects.first())
        vm.onSelectExercise(2L)
        vm.onHistorySelected()
        assertEquals(WorkoutExecutionEffect.OpenHistory(7L, 2L), vm.effects.first())
    }

    @Test
    fun skipped_row_records_dense_set_numbers() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(
            ExerciseSpec(
                id = 1L, workoutId = 7L, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 0, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            )
        )
        val history = FakeHistory(result = Result.success(1L))
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onRepsChanged(1L, 1, "10")
        vm.onCompleteSeriesSet(1L, 1)
        vm.onRepsChanged(1L, 3, "8")
        vm.onCompleteSeriesSet(1L, 3)
        vm.onCompleteExercise(1L)
        runCurrent()
        assertEquals(1, history.writes.size)
        val input = history.writes.single() as SeriesExecutionInput
        assertEquals(listOf(1, 2), input.sets.map { it.setNumber })
        assertEquals(listOf(10, 8), input.sets.map { it.reps })
    }

    @Test
    fun no_writes_on_selection() = runTest {
        setTestMain()
        val repo = FakeWorkoutRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        repo.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val history = FakeHistory()
        val vm = WorkoutExecutionViewModel(7L, repo, history, FakeTimer(), FakeSession())
        awaitReady(vm)
        runCurrent()
        vm.onSelectExercise(2L)
        vm.onNextExercise()
        vm.onPreviousExercise()
        runCurrent()
        assertTrue(history.writes.isEmpty())
    }

    // ---- Fakes ----

    private class FakeWorkoutRepository : WorkoutRepository {
        val workout = MutableStateFlow<WorkoutSummary?>(null)
        val exercises = MutableStateFlow<List<ExerciseSpec>>(emptyList())

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            throw UnsupportedOperationException("not used")

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> = workout
        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> = exercises

        override suspend fun createWorkout(input: WorkoutDraft): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteWorkout(id: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun createExercise(input: ExerciseInput): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteExercise(id: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            throw UnsupportedOperationException("not used")
    }

    private class FakeHistory(
        var result: Result<Long> = Result.success(1L),
        var deferred: CompletableDeferred<Result<Long>>? = null,
        var latestError: Throwable? = null
    ) : ExerciseHistoryRepository {
        val writes = mutableListOf<Any>()
        private val execFlows = mutableMapOf<Long, MutableStateFlow<ExerciseExecutionView?>>()
        private val setFlows = mutableMapOf<Long, MutableStateFlow<ExecutionSetView?>>()

        fun latestExecValue(id: Long, view: ExerciseExecutionView?) {
            execFlows.getOrPut(id) { MutableStateFlow(null) }.value = view
        }

        fun latestSetValue(id: Long, view: ExecutionSetView?) {
            setFlows.getOrPut(id) { MutableStateFlow(null) }.value = view
        }

        override fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>> =
            throw UnsupportedOperationException("not used")

        override fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?> = flow {
            latestError?.let { throw it }
            emitAll(execFlows.getOrPut(exerciseId) { MutableStateFlow(null) })
        }

        override fun observeLatestSet(exerciseId: Long): Flow<ExecutionSetView?> = flow {
            latestError?.let { throw it }
            emitAll(setFlows.getOrPut(exerciseId) { MutableStateFlow(null) })
        }

        override suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long> {
            writes.add(input)
            deferred?.let { return it.await() }
            return result
        }

        override suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long> {
            writes.add(input)
            return result
        }

        override suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long> {
            writes.add(input)
            return result
        }

        override suspend fun deleteExecution(executionId: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override fun observeHistoryPage(query: com.offlineworkout.data.model.HistoryQuery): Flow<List<ExerciseExecutionView>> =
            throw UnsupportedOperationException("not used")

        override fun observeHistoryWindow(
            exerciseId: Long,
            fromWallClockMs: Long?,
            toWallClockMs: Long?
        ): Flow<List<ExerciseExecutionView>> =
            throw UnsupportedOperationException("not used")

        override fun observeHistoryStats(exerciseId: Long): Flow<com.offlineworkout.data.model.HistoryStats> =
            throw UnsupportedOperationException("not used")
    }

    private class FakeTimer(
        var nextStart: RestTimerCommandResult = RestTimerCommandResult.Started
    ) : RestTimerController {
        val stateFlow = MutableStateFlow<RestTimerSnapshot?>(null)
        override val state: StateFlow<RestTimerSnapshot?> = stateFlow
        val starts = mutableListOf<RestRequest>()
        val cancels = mutableListOf<String>()

        override fun start(request: RestRequest): RestTimerCommandResult {
            starts.add(request)
            return nextStart
        }

        override fun cancel(timerId: String): RestTimerCommandResult {
            cancels.add(timerId)
            return RestTimerCommandResult.Cancelled
        }
    }

    private class FakeSession(
        initial: WorkoutSessionState = WorkoutSessionState(
            status = WorkoutSessionStatus.Running(7L),
            workoutId = 7L,
            elapsedSeconds = 0L,
            startedAtWallClockMs = 1L,
            accumulatedMs = 0L,
            lastResumedAtWallClockMs = 1L
        )
    ) : WorkoutSessionController {
        // T9.2 (R-12): the contract is closeable, so the composition root can
        // stop the ticker; fakes have nothing to release.
        override fun close() = Unit

        val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<WorkoutSessionState> = stateFlow
        val started = mutableListOf<Long>()
        var pauses = 0
        var resumes = 0
        var stops = 0

        override fun startOrResume(workoutId: Long) {
            started.add(workoutId)
        }

        override fun pause() {
            pauses += 1
        }

        override fun resume() {
            resumes += 1
        }

        override fun stop() {
            stops += 1
        }
    }
}
