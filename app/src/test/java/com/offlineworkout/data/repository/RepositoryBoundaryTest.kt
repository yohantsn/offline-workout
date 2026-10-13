package com.offlineworkout.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

// T2.1: the public contracts must not leak Room, DAOs, SQLite or data.local.
// Reflection sees erased signatures (Flow/Result erase to their raw types),
// which is exactly what the JVM exposes to consumers.
class RepositoryBoundaryTest {

    private val forbiddenPackageFragments = listOf(
        "androidx.room",
        "androidx.sqlite",
        "android.database",
        "com.offlineworkout.data.local"
    )

    private val allowedClasses = setOf(
        // kotlin.Result erases to java.lang.Object; suspend functions also take
        // kotlin.coroutines.Continuation. Both are part of the contract by design.
        "java.lang.Object",
        "kotlin.coroutines.Continuation"
    )

    @Test
    fun workoutRepository_exposes_no_persistence_types() {
        assertClean(WorkoutRepository::class.java)
    }

    @Test
    fun exerciseHistoryRepository_exposes_no_persistence_types() {
        assertClean(ExerciseHistoryRepository::class.java)
    }

    @Test
    fun public_models_use_no_persistence_types() {
        val models = listOf(
            com.offlineworkout.data.model.WorkoutSummary::class.java,
            com.offlineworkout.data.model.ExerciseSpec::class.java,
            com.offlineworkout.data.model.ExerciseExecutionView::class.java,
            com.offlineworkout.data.model.ExecutionSetView::class.java,
            com.offlineworkout.data.model.WorkoutDraft::class.java,
            com.offlineworkout.data.model.WorkoutUpdate::class.java,
            com.offlineworkout.data.model.ExerciseInput::class.java,
            com.offlineworkout.data.model.SeriesExecutionInput::class.java,
            com.offlineworkout.data.model.TimedExecutionInput::class.java,
            com.offlineworkout.data.model.DistanceTimeExecutionInput::class.java,
            com.offlineworkout.data.model.ExecutionSetInput::class.java,
            com.offlineworkout.data.model.ExerciseType::class.java,
            com.offlineworkout.data.model.HistoryQuery::class.java,
            com.offlineworkout.data.model.HistoryStats::class.java,
            com.offlineworkout.data.model.HistoryPoint::class.java,
            com.offlineworkout.data.model.HistoryChartData::class.java,
            com.offlineworkout.data.model.ExerciseAggregateDraft::class.java,
            com.offlineworkout.data.model.WorkoutAggregateDraft::class.java
        )
        models.forEach { model ->
            (model.declaredFields.map { it.type.name } +
                model.methods.flatMap { listOf(it.returnType.name) + it.parameterTypes.map { p -> p.name } }
                ).forEach { typeName ->
                assertTrue(
                    "forbidden type $typeName in ${model.simpleName}",
                    typeName in allowedClasses || forbiddenPackageFragments.none { typeName.contains(it) }
                )
            }
        }
    }

    @Test
    fun backupRepository_exposes_no_persistence_types() {
        assertClean(BackupRepository::class.java)
    }

    @Test
    fun backup_file_gateway_exposes_no_persistence_types() {
        assertClean(com.offlineworkout.data.backup.BackupFileGateway::class.java)
    }

    @Test
    fun backup_models_and_failures_use_no_persistence_types() {
        val types = listOf(
            BackupDocument::class.java,
            BackupPreview::class.java,
            BackupImportResult::class.java,
            BackupFailure::class.java
        )
        types.forEach { type ->
            (type.declaredFields.map { it.type.name } +
                type.methods.flatMap { listOf(it.returnType.name) + it.parameterTypes.map { p -> p.name } }
                ).forEach { typeName ->
                assertTrue(
                    "forbidden type $typeName in ${type.simpleName}",
                    typeName in allowedClasses || forbiddenPackageFragments.none { typeName.contains(it) }
                )
            }
        }
    }

    private fun assertClean(contract: Class<*>) {
        contract.methods.forEach { method ->
            val types = listOf(method.returnType.name) + method.parameterTypes.map { it.name }
            types.forEach { typeName ->
                assertTrue(
                    "forbidden type $typeName in ${contract.simpleName}.${method.name}",
                    typeName in allowedClasses || forbiddenPackageFragments.none { typeName.contains(it) }
                )
            }
        }
    }
}
