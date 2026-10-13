package com.offlineworkout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// T1.1: exercise DAO. Reordering runs inside a repository transaction (T2.2)
// calling updatePositions; the UNIQUE(workoutId, position) index still applies.
@Dao
interface ExerciseDao {

    @Insert
    suspend fun insert(exercise: ExerciseEntity): Long

    @Insert
    suspend fun insertAll(exercises: List<ExerciseEntity>): List<Long>

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Delete
    suspend fun delete(exercise: ExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE workoutId = :workoutId ORDER BY position ASC")
    suspend fun getForWorkout(workoutId: Long): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE workoutId = :workoutId ORDER BY position ASC")
    fun observeForWorkout(workoutId: Long): Flow<List<ExerciseEntity>>

    // T8.3: backup export order (T8.1 spec): workoutId ASC, position ASC, id ASC.
    @Query("SELECT * FROM exercises ORDER BY workoutId ASC, position ASC, id ASC")
    suspend fun getAllOrdered(): List<ExerciseEntity>

    @Query("SELECT COUNT(*) FROM exercises WHERE workoutId = :workoutId")
    suspend fun countForWorkout(workoutId: Long): Int

    @Query("SELECT MAX(position) FROM exercises WHERE workoutId = :workoutId")
    suspend fun maxPositionForWorkout(workoutId: Long): Int?

    @Update
    suspend fun updatePositions(exercises: List<ExerciseEntity>)

    // T8.1: full wipe for replace-all import.
    @Query("DELETE FROM exercises")
    suspend fun deleteAll()
}
