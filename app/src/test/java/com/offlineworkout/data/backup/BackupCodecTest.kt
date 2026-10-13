package com.offlineworkout.data.backup

import com.offlineworkout.data.local.ExerciseEntity
import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.WorkoutEntity
import com.offlineworkout.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// T8.1: codec determinism and every decode failure mode. Plain JVM: the codec
// has no Android imports.
class BackupCodecTest {

    private fun sampleData() = BackupDataDraft(
        workouts = listOf(
            WorkoutEntity(id = 1L, code = "A", name = "Treino \"A\" \\ forte", createdAt = 1000L),
            WorkoutEntity(id = 2L, code = "B", name = "Treino B", createdAt = 2000L)
        ),
        exercises = listOf(
            ExerciseEntity(
                id = 10L, workoutId = 1L, name = "Supino", type = ExerciseType.SERIES,
                position = 0, restSeconds = 90, targetSets = 3, targetReps = 10,
                targetWeightKg = 60.5, targetDurationSeconds = null, targetDistanceMeters = null
            ),
            ExerciseEntity(
                id = 11L, workoutId = 1L, name = "Prancha", type = ExerciseType.TIMED,
                position = 1, restSeconds = 30, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 60L, targetDistanceMeters = null
            )
        ),
        executions = listOf(
            ExerciseExecutionEntity(
                id = 100L, exerciseId = 10L, performedAt = 5000L, completedAt = 5100L,
                durationSeconds = null, distanceMeters = null
            ),
            ExerciseExecutionEntity(
                id = 101L, exerciseId = 11L, performedAt = 6000L, completedAt = null,
                durationSeconds = 62L, distanceMeters = null
            )
        ),
        sets = listOf(
            ExecutionSetEntity(id = 1000L, executionId = 100L, setNumber = 1, reps = 10, weightKg = 60.5),
            ExecutionSetEntity(id = 1001L, executionId = 100L, setNumber = 2, reps = 8, weightKg = null)
        )
    )

    private fun decodeErrorOf(bytes: ByteArray): BackupDecodeError {
        try {
            BackupCodec.decodeDocument(bytes)
        } catch (e: BackupDecodeException) {
            return e.error
        }
        throw AssertionError("expected BackupDecodeException")
    }

    @Test
    fun round_trip_preserves_everything() {
        val data = sampleData()
        val bytes = BackupCodec.encodeDocument(data, 1730000000000L, "0.1.0")
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("{\"format\":\"offline-workout-backup\",\"schemaVersion\":1,"))
        assertTrue(text.contains("\"appVersion\":\"0.1.0\""))

        val decoded = BackupCodec.decodeDocument(bytes)
        assertEquals(data, decoded)
    }

    @Test
    fun canonical_bytes_are_stable() {
        val first = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0")
        val second = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0")
        assertTrue(first.contentEquals(second))
        // No insignificant whitespace anywhere.
        assertTrue(!first.toString(Charsets.UTF_8).contains(": "))
    }

    @Test
    fun whitespace_and_key_order_do_not_change_verification() {
        val compact = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0").toString(Charsets.UTF_8)
        // Reformat freely: newlines and spaces are insignificant.
        val pretty = compact
            .replace("{", "{\n  ")
            .replace("}", "\n}")
            .replace(",", ",\n  ")
        assertEquals(sampleData(), BackupCodec.decodeDocument(pretty.toByteArray(Charsets.UTF_8)))

        // Reordering the envelope keys is equally insignificant: the checksum
        // covers the canonical `data` tree, not the envelope layout. The
        // document is rebuilt with the envelope keys in reverse order.
        val reordered = reorderEnvelope(compact)
        assertNotEquals(compact, reordered)
        assertEquals(sampleData(), BackupCodec.decodeDocument(reordered.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Rebuilds the envelope with its top-level members in reverse order,
     * leaving every member body (including the hashed `data`) byte-identical.
     */
    private fun reorderEnvelope(compact: String): String {
        val body = compact.removePrefix("{").removeSuffix("}")
        return "{" + splitTopLevelMembers(body).reversed().joinToString(",") + "}"
    }

    /** Splits `{...}` members on top-level commas only (nesting/strings aware). */
    private fun splitTopLevelMembers(body: String): List<String> {
        val members = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (c in body) {
            if (c == ',' && depth == 0 && !inString) {
                members.add(current.toString())
                current.clear()
                continue
            }
            current.append(c)
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        if (current.isNotEmpty()) members.add(current.toString())
        return members
    }

    @Test
    fun document_above_the_technical_limit_is_rejected() {
        val oversized = ByteArray(MAX_BACKUP_BYTES + 1) { '{'.code.toByte() }
        val error = decodeErrorOf(oversized)
        assertEquals(BackupDecodeError.TooLarge(MAX_BACKUP_BYTES + 1, MAX_BACKUP_BYTES), error)
    }

    @Test
    fun document_just_below_the_limit_is_parsed_not_rejected_for_size() {
        // A syntactically valid, correctly hashed document of legal size is
        // accepted: the limit must not reject normal files.
        val bytes = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0")
        assertTrue(bytes.size < MAX_BACKUP_BYTES)
        assertEquals(sampleData(), BackupCodec.decodeDocument(bytes))
    }

    @Test
    fun deeply_nested_document_fails_without_overflowing_the_stack() {
        // 200k nested arrays: an unbounded recursive parser dies with
        // StackOverflowError; the depth limit turns it into Corrupt.
        val depth = 200_000
        val hostile = "[".repeat(depth) + "]".repeat(depth)
        val error = decodeErrorOf(hostile.toByteArray(Charsets.UTF_8))
        assertTrue(error is BackupDecodeError.Corrupt)
    }

    @Test
    fun nesting_up_to_the_limit_is_accepted_and_beyond_is_rejected() {
        // The root counts as level 0, so MAX_JSON_DEPTH + 1 nested arrays is
        // the deepest legitimate document.
        fun depthOf(levels: Int): Int {
            val nested = "[".repeat(levels) + "]".repeat(levels)
            var value: JsonValue = BackupJsonParser.parse(nested)
            var depth = 0
            while (value is JsonValue.Arr && value.items.isNotEmpty()) {
                depth++
                value = value.items.single()
            }
            return depth
        }
        assertEquals(MAX_JSON_DEPTH, depthOf(MAX_JSON_DEPTH + 1))
        val tooDeep = "[".repeat(MAX_JSON_DEPTH + 2) + "]".repeat(MAX_JSON_DEPTH + 2)
        assertTrue(
            decodeErrorOf(tooDeep.toByteArray(Charsets.UTF_8)) is BackupDecodeError.Corrupt
        )
    }

    @Test
    fun tampered_value_is_checksum_mismatch() {
        val bytes = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0")
        val tampered = bytes.toString(Charsets.UTF_8).replace("Supino", "Supina").toByteArray(Charsets.UTF_8)
        val error = decodeErrorOf(tampered)
        assertTrue(error is BackupDecodeError.ChecksumMismatch)
    }

    @Test
    fun truncated_and_garbage_are_corrupt() {
        val bytes = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0")
        assertTrue(decodeErrorOf(bytes.copyOf(bytes.size / 2)) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf("not json at all".toByteArray(Charsets.UTF_8)) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf("\"just a string\"".toByteArray(Charsets.UTF_8)) is BackupDecodeError.Corrupt)
    }

    @Test
    fun broken_utf8_is_corrupt() {
        assertTrue(
            decodeErrorOf(byteArrayOf(0x7B, 0x22, 0xE2.toByte(), 0x28, 0x22, 0x7D)) is BackupDecodeError.Corrupt
        )
    }

    @Test
    fun decode_never_throws_a_raw_error() {
        // A hostile file must degrade to Corrupt, never to OutOfMemoryError or
        // StackOverflowError escaping the codec (B-1).
        val hostile = ("[".repeat(300_000) + "1" + "]".repeat(300_000)).toByteArray(Charsets.UTF_8)
        val error = decodeErrorOf(hostile)
        assertTrue(error is BackupDecodeError.Corrupt)
    }

    @Test
    fun wrong_format_is_corrupt() {
        val text = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0").toString(Charsets.UTF_8)
        val other = text.replace("offline-workout-backup", "other-format").toByteArray(Charsets.UTF_8)
        assertTrue(decodeErrorOf(other) is BackupDecodeError.Corrupt)
    }

    @Test
    fun versions_rejected_as_unsupported() {
        fun withVersion(raw: String): BackupDecodeError {
            val text = BackupCodec.encodeDocument(sampleData(), 1L, "0.1.0").toString(Charsets.UTF_8)
            val patched = text.replace("\"schemaVersion\":1", "\"schemaVersion\":$raw")
            return decodeErrorOf(patched.toByteArray(Charsets.UTF_8))
        }
        assertEquals(BackupDecodeError.UnsupportedVersion(0), withVersion("0"))
        assertEquals(BackupDecodeError.UnsupportedVersion(2), withVersion("2"))
        assertEquals(BackupDecodeError.UnsupportedVersion(-1), withVersion("\"1\""))
    }

    @Test
    fun missing_and_mistyped_fields_are_corrupt() {
        // Structural rules are checked after the hash, so these documents are
        // re-signed with a correct checksum to reach the payload decoder.
        fun signed(rawData: String): ByteArray {
            val dataBytes = rawData.toByteArray(Charsets.UTF_8)
            val hash = BackupCodec.sha256Hex(dataBytes)
            return ("{\"format\":\"offline-workout-backup\",\"schemaVersion\":1," +
                "\"exportedAtWallClockMs\":1,\"appVersion\":\"0.1.0\"," +
                "\"integrity\":{\"algorithm\":\"SHA-256\",\"value\":\"$hash\"}," +
                "\"data\":$rawData}").toByteArray(Charsets.UTF_8)
        }
        val data = BackupCodec.encodeData(sampleData()).toString(Charsets.UTF_8)

        // Missing envelope data member.
        val hash = BackupCodec.sha256Hex(data.toByteArray(Charsets.UTF_8))
        val noData = ("{\"format\":\"offline-workout-backup\",\"schemaVersion\":1," +
            "\"exportedAtWallClockMs\":1,\"appVersion\":\"0.1.0\"," +
            "\"integrity\":{\"algorithm\":\"SHA-256\",\"value\":\"$hash\"}}").toByteArray(Charsets.UTF_8)
        assertTrue(decodeErrorOf(noData) is BackupDecodeError.Corrupt)

        // Mistyped id, unknown enum value, wrong container type.
        assertTrue(decodeErrorOf(signed(data.replace("\"id\":1,", "\"id\":\"1\","))) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf(signed(data.replace("\"SERIES\"", "\"CARDIO\""))) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf(signed("{\"workouts\":[]}")) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf(signed("[]")) is BackupDecodeError.Corrupt)
        assertTrue(decodeErrorOf(signed(data.replace("\"createdAt\":1000", "\"createdAt\":null"))) is BackupDecodeError.Corrupt)
        // id 0 is structurally decodable: positivity is a repository rule.
        val zeroId = signed(data.replace("\"id\":1,", "\"id\":0,"))
        assertEquals(0L, BackupCodec.decodeDocument(zeroId).workouts.first().id)
    }

    @Test
    fun non_finite_numbers_rejected() {
        val data = sampleData().copy(
            sets = listOf(
                ExecutionSetEntity(id = 1L, executionId = 1L, setNumber = 1, reps = 1, weightKg = Double.POSITIVE_INFINITY)
            )
        )
        val bytes = BackupCodec.encodeDocument(data, 1L, "0.1.0")
        assertTrue(decodeErrorOf(bytes) is BackupDecodeError.Corrupt)
    }
}
