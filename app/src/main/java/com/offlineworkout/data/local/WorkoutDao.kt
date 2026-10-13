package com.offlineworkout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// T1.1: workout DAO. Writes are suspend; observable reads are Flow.
@Dao
interface WorkoutDao {

    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Update
    suspend fun update(workout: WorkoutEntity)

    @Delete
    suspend fun delete(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getById(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE code = :code")
    suspend fun getByCode(code: String): WorkoutEntity?

    @Query("SELECT * FROM workouts")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeById(id: Long): Flow<WorkoutEntity?>

    // Redesign (lista): agregação somente leitura — nenhuma entidade, índice
    // ou coluna nova. Treino sem exercício agrega 0/0 via LEFT JOIN + COALESCE.
    @Query("""
      SELECT w.id, w.code, w.name, w.createdAt,
             COUNT(e.id) AS exerciseCount,
             COALESCE(SUM(e.restSeconds), 0) AS totalRestSeconds
      FROM workouts w
      LEFT JOIN exercises e ON e.workoutId = w.id
      GROUP BY w.id, w.code, w.name, w.createdAt
    """)
    fun observeAllAggregated(): Flow<List<WorkoutAggregateRow>>

    @Query("""
      SELECT w.id, w.code, w.name, w.createdAt,
             COUNT(e.id) AS exerciseCount,
             COALESCE(SUM(e.restSeconds), 0) AS totalRestSeconds
      FROM workouts w
      LEFT JOIN exercises e ON e.workoutId = w.id
      WHERE w.id = :id
      GROUP BY w.id, w.code, w.name, w.createdAt
    """)
    fun observeByIdAggregated(id: Long): Flow<WorkoutAggregateRow?>

    @Query("SELECT * FROM exercises WHERE workoutId = :workoutId ORDER BY position ASC")
    fun observeExercises(workoutId: Long): Flow<List<ExerciseEntity>>

    @Query("SELECT COUNT(*) FROM workouts WHERE code = :code")
    suspend fun countByCode(code: String): Int

    // T8.1: export order (code ASC, id ASC) and full wipe for replace-all.
    @Query("SELECT * FROM workouts ORDER BY code ASC, id ASC")
    suspend fun getAllOrdered(): List<WorkoutEntity>

    @Query("DELETE FROM workouts")
    suspend fun deleteAll()
}
