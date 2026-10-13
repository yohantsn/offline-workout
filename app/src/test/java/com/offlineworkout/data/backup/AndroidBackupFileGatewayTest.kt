package com.offlineworkout.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

// T8.1: SAF gateway. Uses a real ContentResolver with a temporary file-backed
// provider target, so streaming, missing-uri handling and the FileProvider
// share path are exercised without a device.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBackupFileGatewayTest {

    // Robolectric roots its sandbox under a symlinked temp dir on macOS
    // (/var -> /private/var). FileProvider canonicalizes the candidate file but
    // not the configured cache-path root, so a symlinked cacheDir makes every
    // provider lookup miss. This wrapper reports a canonical cacheDir (and
    // returns itself as the application context, which the gateway keeps).
    private val context: Context
        get() {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val canonical = File(base.cacheDir.canonicalPath)
            return object : android.content.ContextWrapper(base) {
                override fun getCacheDir(): File = canonical
                override fun getApplicationContext(): Context = this
            }
        }

    private val gateway: AndroidBackupFileGateway get() = AndroidBackupFileGateway(context)

    private fun tempUri(name: String): Uri {
        val dir = File(context.cacheDir, "saftest").apply { mkdirs() }
        return Uri.fromFile(File(dir, name))
    }

    @Before
    fun resetFileProviderCache() {
        // androidx FileProvider memoizes its parsed path strategy in a static
        // field, and Robolectric reuses the sandbox classloader across test
        // methods: without this the first test's cacheDir root would be reused
        // and every later lookup would miss.
        val field = FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableMap<String, Any>).clear()
    }

    @Test
    fun write_then_read_round_trips_bytes() = runTest {
        val uri = tempUri("roundtrip.json")
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

        assertTrue(gateway.writeBytes(uri.toString(), payload).isSuccess)
        val read = gateway.readBytes(uri.toString())
        assertTrue(read.isSuccess)
        assertArrayEquals(payload, read.getOrThrow())
        assertEquals(payload.size.toLong(), read.getOrThrow().size.toLong())
    }

    @Test
    fun read_of_missing_document_fails_without_throwing() = runTest {
        val result = gateway.readBytes(Uri.fromFile(File(context.cacheDir, "absent.json")).toString())
        assertTrue(result.isFailure)
    }

    @Test
    fun sanitized_name_strips_paths_and_forces_json() {
        val g = gateway
        assertEquals("passwd.json", g.sanitize("../../etc/passwd"))
        assertEquals("b_c.json", g.sanitize("a/b c"))
        assertEquals("backup.json", g.sanitize("   "))
        assertEquals("plan.json", g.sanitize("plan"))
        assertEquals("plan.json", g.sanitize("plan.JSON"))
    }

    @Test
    fun share_stages_file_in_cache_and_grants_read() = runTest {
        val payload = "{\"a\":1}".toByteArray(Charsets.UTF_8)
        val result = gateway.shareDocument(
            "offline-workout-backup-20240101-120000.json",
            BACKUP_MIME_TYPE,
            payload
        )
        assertTrue("share failed: $result", result.isSuccess)

        val staged = File(File(context.cacheDir, "backups"), "offline-workout-backup-20240101-120000.json")
        assertTrue(staged.exists())
        assertArrayEquals(payload, staged.readBytes())

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", staged)
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".fileprovider", uri.authority)
        // The grant actually resolves: the receiving app can read the stream.
        val stream = context.contentResolver.openInputStream(uri)
        assertArrayEquals(payload, stream?.use { it.readBytes() })
    }

    @Test
    fun read_above_the_technical_limit_fails_with_too_large() = runTest {
        // B-1: the stream is bounded, so an oversized document fails instead of
        // being materialised (and never reaches the heap ceiling).
        val big = File(context.cacheDir, "saftest/huge.json")
        big.parentFile?.mkdirs()
        big.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            var written = 0
            while (written <= MAX_BACKUP_BYTES) {
                out.write(chunk)
                written += chunk.size
            }
        }
        val result = gateway.readBytes(Uri.fromFile(big).toString())
        assertTrue(result.isFailure)
        assertTrue(
            "expected BackupDocumentTooLargeException, got $result",
            result.exceptionOrNull() is BackupDocumentTooLargeException
        )
        assertTrue(big.delete())
    }

    @Test
    fun read_just_below_the_technical_limit_succeeds() = runTest {
        val file = File(context.cacheDir, "saftest/limit.json")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(MAX_BACKUP_BYTES - 1024))
        val result = gateway.readBytes(Uri.fromFile(file).toString())
        assertTrue("read failed: $result", result.isSuccess)
        assertEquals(MAX_BACKUP_BYTES - 1024, result.getOrThrow().size)
        assertTrue(file.delete())
    }

    @Test
    fun sharing_keeps_a_single_staged_copy() = runTest {
        gateway.shareDocument("first.json", BACKUP_MIME_TYPE, byteArrayOf(1))
        gateway.shareDocument("second.json", BACKUP_MIME_TYPE, byteArrayOf(2))
        val staged = File(context.cacheDir, "backups").listFiles()!!.toList()
        assertEquals(listOf("second.json"), staged.map { it.name })
    }

    @Test
    fun purge_keeps_the_live_share_and_removes_expired_copies() = runTest {
        val clock = { 2_000_000_000L }
        val g = AndroidBackupFileGateway(context, wallClock = clock)
        g.shareDocument("live.json", BACKUP_MIME_TYPE, byteArrayOf(1))
        // A payload left behind by an earlier process.
        val stale = File(File(context.cacheDir, "backups"), "stale.json").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(clock() - AndroidBackupFileGateway.STAGED_FILE_MAX_AGE_MS - 1_000L)
        }
        assertTrue(g.purgeExpiredStagedCache().isSuccess)
        val remaining = File(context.cacheDir, "backups").listFiles()!!.map { it.name }
        assertEquals(listOf("live.json"), remaining)
        assertTrue(!stale.exists())
    }

    @Test
    fun purge_removes_a_stale_newest_copy_too() = runTest {
        val clock = { 2_000_000_000L }
        val g = AndroidBackupFileGateway(context, wallClock = clock)
        File(context.cacheDir, "backups").mkdirs()
        val stale = File(File(context.cacheDir, "backups"), "stale.json").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(clock() - AndroidBackupFileGateway.STAGED_FILE_MAX_AGE_MS - 1_000L)
        }
        assertTrue(g.purgeExpiredStagedCache().isSuccess)
        assertTrue(!stale.exists())
    }

    @Test
    fun clear_staged_cache_is_idempotent_and_removes_everything() = runTest {
        val g = gateway
        g.shareDocument("one.json", BACKUP_MIME_TYPE, byteArrayOf(1))
        g.shareDocument("two.json", BACKUP_MIME_TYPE, byteArrayOf(2))
        assertEquals(1, File(context.cacheDir, "backups").listFiles()!!.size)
        assertTrue(g.clearStagedCache().isSuccess)
        assertEquals(0, File(context.cacheDir, "backups").listFiles()!!.size)
        // Running again on an empty cache is a no-op, not a failure.
        assertTrue(g.clearStagedCache().isSuccess)
        assertTrue(g.purgeExpiredStagedCache().isSuccess)
        assertEquals(0, File(context.cacheDir, "backups").listFiles()!!.size)
    }

    @Test
    fun startup_cleanup_blocking_removes_staged_files_without_throwing() = runTest {
        val g = gateway
        g.shareDocument("leftover.json", BACKUP_MIME_TYPE, byteArrayOf(1))
        assertTrue(File(File(context.cacheDir, "backups"), "leftover.json").exists())
        g.clearStagedCacheBlocking()
        assertEquals(0, File(context.cacheDir, "backups").listFiles()!!.size)
        // Idempotent: a second startup with an empty cache is a no-op.
        g.clearStagedCacheBlocking()
    }

    @Test
    fun describe_returns_name_size_and_modified() = runTest {
        val authority = "com.test.describe.full"
        ShadowContentResolver.registerProviderInternal(
            authority,
            MetaProvider(
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                arrayOf<Any?>("treino-backup.json", 1331L, 1_714_953_600_000L)
            )
        )
        val info = gateway.describe("content://$authority/treino-backup.json").getOrThrow()
        assertEquals("treino-backup.json", info.fileName)
        assertEquals(1331L, info.sizeBytes)
        assertEquals(1_714_953_600_000L, info.modifiedAtEpochMs)
    }

    @Test
    fun describe_without_modified_column_falls_back_to_null() = runTest {
        val authority = "com.test.describe.nomodified"
        ShadowContentResolver.registerProviderInternal(
            authority,
            MetaProvider(
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                arrayOf<Any?>("b.json", 42L)
            )
        )
        val info = gateway.describe("content://$authority/b.json").getOrThrow()
        assertEquals("b.json", info.fileName)
        assertEquals(42L, info.sizeBytes)
        assertNull(info.modifiedAtEpochMs)
    }

    @Test
    fun describe_of_unknown_provider_fails_without_throwing() = runTest {
        assertTrue(gateway.describe("content://unknown.authority.invalid/file").isFailure)
    }

    private class MetaProvider(
        private val columns: Array<String>,
        private val values: Array<Any?>
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor {
            return MatrixCursor(columns).apply { addRow(values) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ): Int = 0
    }

    @Test
    fun share_uri_grant_is_read_only_and_temporary() = runTest {
        gateway.shareDocument("share-me.json", BACKUP_MIME_TYPE, byteArrayOf(1))
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val target = started.getParcelableExtra<android.content.Intent>(
            android.content.Intent.EXTRA_INTENT
        )!!
        assertEquals(android.content.Intent.ACTION_SEND, target.action)
        assertEquals(BACKUP_MIME_TYPE, target.type)
        assertTrue(target.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(
            0,
            target.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        assertTrue(target.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
