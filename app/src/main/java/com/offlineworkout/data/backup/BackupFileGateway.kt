package com.offlineworkout.data.backup

// T8.1: file gateway boundary. Isolates ContentResolver, FileProvider and
// Intent from ViewModels and repositories; carries bytes/URIs, never Room.
interface BackupFileGateway {
    suspend fun writeBytes(uriString: String, bytes: ByteArray): Result<Unit>
    suspend fun readBytes(uriString: String): Result<ByteArray>
    suspend fun shareDocument(fileName: String, mimeType: String, bytes: ByteArray): Result<Unit>

    // T8.3: cleanup of the staged share cache. clearStagedCache() removes
    // everything (startup: any pending share is dead with the process), while
    // purgeExpiredStagedCache() only removes files older than the technical
    // TTL, so a share that is still in flight keeps its payload. Both are
    // idempotent and never throw: a failed cleanup is retried next startup.
    suspend fun clearStagedCache(): Result<Unit>
    suspend fun purgeExpiredStagedCache(): Result<Unit>
}

// T8.3: the selected document is above the measured technical limit. Carried
// as its own type so the ViewModel can offer "escolher outro arquivo" instead
// of a retry that would fail identically.
class BackupDocumentTooLargeException(
    val maxBytes: Int
) : Exception("backup document exceeds the technical size limit")
