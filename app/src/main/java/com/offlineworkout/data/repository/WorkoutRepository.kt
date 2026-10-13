package com.offlineworkout.data.repository

import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import kotlinx.coroutines.flow.Flow

// T2.1: public contract for workout/exercise configuration. Reads are Flow
// (failures surface as RepositoryException); writes are suspend Result with
// RepositoryFailure. No Room/DAO/SQLite/data.local imports.
// T4.1: aggregate operations save workout + exercises in one transaction; the
// editor uses only these two methods, never chained single writes.
interface WorkoutRepository {
    fun observeWorkouts(): Flow<List<WorkoutSummary>>
    fun observeWorkout(id: Long): Flow<WorkoutSummary?>
    fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>>

    suspend fun createWorkout(input: WorkoutDraft): Result<Long>
    suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit>
    suspend fun deleteWorkout(id: Long): Result<Unit>
    suspend fun createExercise(input: ExerciseInput): Result<Long>
    suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit>
    suspend fun deleteExercise(id: Long): Result<Unit>
    suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit>

    suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long>
    suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit>
}
