package com.offlineworkout.data.repository

// T8.1: public backup contract. Snapshot bytes in, snapshot bytes out; the UI
// knows file names, counts and failures — never Room, DAOs or entities.
data class BackupDocument(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val schemaVersion: Int,
    val checksum: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupDocument) return false
        return fileName == other.fileName &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes) &&
            schemaVersion == other.schemaVersion &&
            checksum == other.checksum
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + checksum.hashCode()
        return result
    }
}

data class BackupPreview(
    val schemaVersion: Int,
    val checksum: String,
    val workoutCount: Int,
    val exerciseCount: Int,
    val executionCount: Int,
    val executionSetCount: Int,
    val willReplaceAll: Boolean
)

// Carried directly by Result.failure: like RepositoryFailure, it is a
// Throwable by design (the spec's `sealed interface` shape cannot satisfy
// the `Result<T>` contract, so it is a sealed class here with identical
// names). Messages are stable and contain no SQL, paths or internals.
sealed class BackupFailure(message: String) : Exception(message) {
    class Corrupt(reason: String) : BackupFailure(reason)
    class UnsupportedVersion(val version: Int) :
        BackupFailure("unsupported backup version $version")
    class ChecksumMismatch(val expected: String, val actual: String) :
        BackupFailure("backup checksum mismatch")
    class Validation(reason: String) : BackupFailure(reason)
    class ReferentiallyInvalid(reason: String) : BackupFailure(reason)
    class Storage(reason: String) : BackupFailure(reason)
}

interface BackupRepository {
    suspend fun createDocument(): Result<BackupDocument>
    suspend fun preview(bytes: ByteArray): Result<BackupPreview>
    suspend fun importReplaceAll(bytes: ByteArray): Result<BackupImportResult>
}

data class BackupImportResult(
    val workoutCount: Int,
    val exerciseCount: Int,
    val executionCount: Int,
    val executionSetCount: Int
)
