package com.offlineworkout.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

// T8.1: Android gateway. SAF streams via ContentResolver (no storage
// permission, no absolute paths); sharing stages a sanitized file under
// cacheDir/backups and exposes it through FileProvider with a temporary
// read grant. T8.3: reads are bounded by the measured technical limit, the
// staged cache has a TTL plus a startup purge, and Error types that a hostile
// file can trigger (OOM/StackOverflow) surface as failures, never crashes.
class AndroidBackupFileGateway(
    context: Context,
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) : BackupFileGateway {

    private val appContext = context.applicationContext

    override suspend fun writeBytes(uriString: String, bytes: ByteArray): Result<Unit> =
        runGateway {
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(uriString)
                    ?: throw IOException("invalid destination")
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: throw IOException("cannot open destination")
            }
        }

    /**
     * Bounded read: the stream is consumed in chunks and never materialises
     * more than [MAX_BACKUP_BYTES] + 1 byte, so an oversized or hostile
     * document fails with [BackupDocumentTooLargeException] instead of
     * exhausting the heap.
     */
    override suspend fun readBytes(uriString: String): Result<ByteArray> =
        runGateway {
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(uriString)
                    ?: throw IOException("invalid source")
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("cannot open source")
                stream.use { readBounded(it) }
            }
        }

    override suspend fun shareDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<Unit> = runGateway {
        withContext(Dispatchers.IO) {
            val dir = stagedDir()
            // At most one staged copy exists: no previous share is in flight
            // here, so every older payload goes before the new one is written.
            purge(dir, keepFreshest = false)
            val safeName = sanitize(fileName)
            val file = File(dir, safeName)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(
                appContext,
                appContext.packageName + ".fileprovider",
                file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override suspend fun clearStagedCache(): Result<Unit> = runGateway {
        withContext(Dispatchers.IO) { clearStagedCacheBlocking() }
    }

    // Redesign (prévia): DISPLAY_NAME/SIZE via OpenableColumns; data via
    // DocumentsContract.COLUMN_LAST_MODIFIED quando o provedor expõe (SAF
    // expõe, provedores genéricos podem omitir → null, nunca inventado).
    override suspend fun describe(uriString: String): Result<BackupFileInfo> = runGateway {
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString) ?: throw IOException("invalid source")
            val resolver = appContext.contentResolver
            val cursor = resolver.query(uri, null, null, null, null)
                ?: throw IOException("cannot describe source")
            cursor.use {
                if (!it.moveToFirst()) throw IOException("empty metadata")
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val fileName = (if (nameIndex >= 0) it.getString(nameIndex) else null)
                    ?: uri.lastPathSegment
                    ?: GENERIC_BACKUP_FILE_NAME
                val sizeBytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                    it.getLong(sizeIndex)
                } else {
                    probeSizeBytes(resolver, uri)
                }
                val modifiedAtEpochMs = if (modifiedIndex >= 0 && !it.isNull(modifiedIndex)) {
                    it.getLong(modifiedIndex)
                } else {
                    null
                }
                BackupFileInfo(fileName, sizeBytes, modifiedAtEpochMs)
            }
        }
    }

    private fun probeSizeBytes(resolver: android.content.ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)

    /**
     * Startup variant. The composition root calls this once when the process
     * container is created: it only unlinks a handful of small files in the
     * app cache, it is idempotent, and it touches no database and no scope.
     */
    fun clearStagedCacheBlocking() {
        stagedDir().listFiles()?.forEach { it.delete() }
    }

    override suspend fun purgeExpiredStagedCache(): Result<Unit> = runGateway {
        withContext(Dispatchers.IO) { purge(stagedDir(), keepFreshest = true) }
    }

    private fun stagedDir(): File = File(appContext.cacheDir, "backups").apply { mkdirs() }

    /**
     * Removes staged payloads. With [keepFreshest] the newest file survives
     * while it is still inside the TTL, because that one belongs to a share
     * that may still be in flight; everything at or past the TTL always goes.
     */
    private fun purge(dir: File, keepFreshest: Boolean) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        val newest = if (keepFreshest) files.lastOrNull() else null
        val cutoff = wallClock() - STAGED_FILE_MAX_AGE_MS
        files.forEach { file ->
            val isLiveShare = keepFreshest && file === newest && file.lastModified() >= cutoff
            if (!isLiveShare) file.delete()
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var out = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_BYTES) throw BackupDocumentTooLargeException(MAX_BACKUP_BYTES)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    internal fun sanitize(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        // Strip path components, then anything outside the safe set, and
        // fall back to a fixed name when nothing usable survives.
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim { it == '_' || it == '.' || it == '-' }
            .ifBlank { "backup" }
        // Deterministic extension: the mime type is always application/json.
        val stem = cleaned.removeSuffix(".json").removeSuffix(".JSON")
        return "$stem.json"
    }

    private suspend fun <T> runGateway(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        // A hostile file must degrade to a failure, never kill the process.
        Result.failure(BackupDocumentTooLargeException(MAX_BACKUP_BYTES))
    } catch (e: StackOverflowError) {
        Result.failure(BackupDecodeException(BackupDecodeError.Corrupt("document nesting is too deep")))
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        // T8.3: technical TTL, not a product setting. A share payload is only
        // needed until the receiving app has copied it; one hour is far beyond
        // any foreground share and bounds the cache to a single small file.
        const val STAGED_FILE_MAX_AGE_MS = 60L * 60L * 1000L

        // Redesign (prévia): último recurso quando o provedor não informa
        // nome algum para o documento.
        const val GENERIC_BACKUP_FILE_NAME = "backup.json"
    }
}
