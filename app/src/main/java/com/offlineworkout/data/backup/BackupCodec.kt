package com.offlineworkout.data.backup

import com.offlineworkout.data.local.ExerciseEntity
import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.WorkoutEntity
import com.offlineworkout.data.model.ExerciseType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

// T8.1: backup schema v1 codec. Canonical bytes (fixed key order, no
// insignificant whitespace) feed the SHA-256 integrity hash; export and import
// share this exact code. Parsing helpers distinguish null from absence for
// required fields. Pure except entity holders (same data layer, no Room).
const val BACKUP_FORMAT = "offline-workout-backup"
const val BACKUP_SCHEMA_VERSION = 1
const val BACKUP_MIME_TYPE = "application/json"

// T8.3: technical limits (NOT product decisions — T8.1 explicitly leaves the
// maximum backup size undefined and asks the implementation to measure before
// fixing a number).
//
// Measured retained footprint of decoding (JVM, heap measured after GC with
// the source bytes, the decoded text, the whole parse tree and the canonical
// re-serialization all alive at once):
//   587 KB -> 6.6 MB   (11.2x)
//  1.68 MB -> 18.6 MB  (10.6x)
//  3.36 MB -> 37.2 MB  (10.6x)
// 4 MiB of JSON therefore peaks at ~46 MB of heap, which leaves room for the
// entity draft, Room and the UI on the smallest supported per-app heap, and it
// is ~7x larger than the ~0.6 MB a very heavy real history produces (240
// exercises / 9,600 executions / 28,800 sets measured above). Data beyond the
// limit fails loudly (export: Storage, import: Corrupt + "escolher outro
// arquivo") instead of crashing. See README "Limite técnico de tamanho".
const val MAX_BACKUP_BYTES = 4 * 1024 * 1024

// T8.3: the v1 document nests root -> data -> array -> object (depth 4). The
// parser is recursive, so a hostile file of nested brackets would otherwise
// blow the stack; this caps recursion far above anything legitimate.
const val MAX_JSON_DEPTH = 64

data class BackupDataDraft(
    val workouts: List<WorkoutEntity>,
    val exercises: List<ExerciseEntity>,
    val executions: List<ExerciseExecutionEntity>,
    val sets: List<ExecutionSetEntity>
)

sealed interface BackupDecodeError {
    data class Corrupt(val reason: String) : BackupDecodeError
    data class UnsupportedVersion(val version: Int) : BackupDecodeError
    data class ChecksumMismatch(val expected: String, val actual: String) : BackupDecodeError
    data class Validation(val reason: String) : BackupDecodeError
    data class ReferentiallyInvalid(val reason: String) : BackupDecodeError

    // T8.3: the file is readable but above the measured technical limit. It is
    // reported as a file error (not a storage error) so the UI offers
    // "escolher outro arquivo", which is the only recovery that can succeed.
    data class TooLarge(val actualBytes: Int, val maxBytes: Int) : BackupDecodeError
}

object BackupCodec {

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encodeData(data: BackupDataDraft): ByteArray {
        return BackupJsonWriter.write(encodeDataValue(data)).toByteArray(Charsets.UTF_8)
    }

    fun encodeDataValue(data: BackupDataDraft): JsonValue {
        val root = LinkedHashMap<String, JsonValue>()
        root["workouts"] = JsonValue.Arr(data.workouts.map { encodeWorkout(it) })
        root["exercises"] = JsonValue.Arr(data.exercises.map { encodeExercise(it) })
        root["executions"] = JsonValue.Arr(data.executions.map { encodeExecution(it) })
        root["executionSets"] = JsonValue.Arr(data.sets.map { encodeSet(it) })
        return JsonValue.Obj(root)
    }

    /**
     * Canonical document plus the SHA-256 of its canonical `data` bytes, which
     * is exactly the `integrity.value` inside it.
     *
     * T9.2 (R-17): encoding and hashing used to happen twice on export — the
     * first pass was thrown away and the caller re-serialized the whole dataset
     * just to recompute the hash. Returning both from a single pass keeps the v1
     * bytes and the checksum value bit-identical while halving the CPU.
     */
    data class EncodedDocument(val bytes: ByteArray, val checksum: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodedDocument) return false
            return bytes.contentEquals(other.bytes) && checksum == other.checksum
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + checksum.hashCode()
    }

    fun encodeDocument(
        data: BackupDataDraft,
        exportedAtWallClockMs: Long,
        appVersion: String
    ): ByteArray = encodeDocumentWithChecksum(data, exportedAtWallClockMs, appVersion).bytes

    fun encodeDocumentWithChecksum(
        data: BackupDataDraft,
        exportedAtWallClockMs: Long,
        appVersion: String
    ): EncodedDocument {
        val dataValue = encodeDataValue(data)
        val dataBytes = BackupJsonWriter.write(dataValue).toByteArray(Charsets.UTF_8)
        val checksum = sha256Hex(dataBytes)
        val root = LinkedHashMap<String, JsonValue>()
        root["format"] = JsonValue.Str(BACKUP_FORMAT)
        root["schemaVersion"] = JsonValue.Num.ofLong(BACKUP_SCHEMA_VERSION.toLong())
        root["exportedAtWallClockMs"] = JsonValue.Num.ofLong(exportedAtWallClockMs)
        root["appVersion"] = JsonValue.Str(appVersion)
        val integrity = LinkedHashMap<String, JsonValue>()
        integrity["algorithm"] = JsonValue.Str("SHA-256")
        integrity["value"] = JsonValue.Str(checksum)
        root["integrity"] = JsonValue.Obj(integrity)
        root["data"] = dataValue
        val bytes = BackupJsonWriter.write(JsonValue.Obj(root)).toByteArray(Charsets.UTF_8)
        return EncodedDocument(bytes, checksum)
    }

    fun decodeDocument(bytes: ByteArray): BackupDataDraft {
        // T8.3: refuse oversized payloads before any parsing work, so a huge
        // or hostile file can never be materialised as text or as a tree.
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw BackupDecodeException(BackupDecodeError.TooLarge(bytes.size, MAX_BACKUP_BYTES))
        }
        val text = decodeUtf8Strict(bytes)
        val root = try {
            BackupJsonParser.parse(text)
        } catch (e: JsonParseException) {
            throw BackupDecodeException(BackupDecodeError.Corrupt("invalid JSON: ${e.message}"))
        }
        if (root !is JsonValue.Obj) throw BackupDecodeException(BackupDecodeError.Corrupt("root must be an object"))
        val format = (root.entries["format"] as? JsonValue.Str)?.value
            ?: throw BackupDecodeException(BackupDecodeError.Corrupt("missing format"))
        if (format != BACKUP_FORMAT) {
            throw BackupDecodeException(BackupDecodeError.Corrupt("unknown format: $format"))
        }
        val versionRaw = root.entries["schemaVersion"]
            ?: throw BackupDecodeException(BackupDecodeError.Corrupt("missing schemaVersion"))
        val version = (versionRaw as? JsonValue.Num)?.longValue
        if (version == null || version != BACKUP_SCHEMA_VERSION.toLong()) {
            val reported = (versionRaw as? JsonValue.Num)?.longValue?.toInt() ?: -1
            throw BackupDecodeException(BackupDecodeError.UnsupportedVersion(reported))
        }
        val integrity = (root.entries["integrity"] as? JsonValue.Obj)?.entries
            ?: throw BackupDecodeException(BackupDecodeError.Corrupt("missing integrity"))
        val algorithm = (integrity["algorithm"] as? JsonValue.Str)?.value
        if (algorithm != "SHA-256") {
            throw BackupDecodeException(BackupDecodeError.Corrupt("unsupported integrity algorithm: $algorithm"))
        }
        val expected = (integrity["value"] as? JsonValue.Str)?.value
            ?: throw BackupDecodeException(BackupDecodeError.Corrupt("missing integrity value"))
        val dataValue = root.entries["data"]
            ?: throw BackupDecodeException(BackupDecodeError.Corrupt("missing data"))
        val canonical = BackupJsonWriter.write(dataValue).toByteArray(Charsets.UTF_8)
        val actual = sha256Hex(canonical)
        if (!actual.equals(expected, ignoreCase = false)) {
            throw BackupDecodeException(BackupDecodeError.ChecksumMismatch(expected, actual))
        }
        return decodeData(dataValue)
    }

    fun decodeUtf8Strict(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw BackupDecodeException(BackupDecodeError.Corrupt("invalid UTF-8"))
        }
    }

    // ---- record encoders (spec field order) ----

    private fun encodeWorkout(w: WorkoutEntity): JsonValue = objOf(
        "id" to JsonValue.Num.ofLong(w.id),
        "code" to JsonValue.Str(w.code),
        "name" to JsonValue.Str(w.name),
        "createdAt" to JsonValue.Num.ofLong(w.createdAt)
    )

    private fun encodeExercise(e: ExerciseEntity): JsonValue = objOf(
        "id" to JsonValue.Num.ofLong(e.id),
        "workoutId" to JsonValue.Num.ofLong(e.workoutId),
        "name" to JsonValue.Str(e.name),
        "type" to JsonValue.Str(e.type.name),
        "position" to JsonValue.Num.ofLong(e.position.toLong()),
        "restSeconds" to JsonValue.Num.ofLong(e.restSeconds.toLong()),
        "targetSets" to optInt(e.targetSets),
        "targetReps" to optInt(e.targetReps),
        "targetWeightKg" to optDouble(e.targetWeightKg),
        "targetDurationSeconds" to optLong(e.targetDurationSeconds),
        "targetDistanceMeters" to optDouble(e.targetDistanceMeters)
    )

    private fun encodeExecution(e: ExerciseExecutionEntity): JsonValue = objOf(
        "id" to JsonValue.Num.ofLong(e.id),
        "exerciseId" to JsonValue.Num.ofLong(e.exerciseId),
        "performedAt" to JsonValue.Num.ofLong(e.performedAt),
        "completedAt" to optLong(e.completedAt),
        "durationSeconds" to optLong(e.durationSeconds),
        "distanceMeters" to optDouble(e.distanceMeters)
    )

    private fun encodeSet(s: ExecutionSetEntity): JsonValue = objOf(
        "id" to JsonValue.Num.ofLong(s.id),
        "executionId" to JsonValue.Num.ofLong(s.executionId),
        "setNumber" to JsonValue.Num.ofLong(s.setNumber.toLong()),
        "reps" to JsonValue.Num.ofLong(s.reps.toLong()),
        "weightKg" to optDouble(s.weightKg)
    )

    private fun objOf(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        pairs.forEach { (key, value) -> map[key] = value }
        return JsonValue.Obj(map)
    }

    private fun optInt(value: Int?): JsonValue =
        if (value == null) JsonValue.Null else JsonValue.Num.ofLong(value.toLong())

    private fun optLong(value: Long?): JsonValue =
        if (value == null) JsonValue.Null else JsonValue.Num.ofLong(value)

    private fun optDouble(value: Double?): JsonValue =
        if (value == null) JsonValue.Null else JsonValue.Num.ofDouble(value)

    // ---- record decoders ----

    private fun decodeData(value: JsonValue): BackupDataDraft {
        if (value !is JsonValue.Obj) throw fail("data must be an object")
        fun arr(key: String): List<JsonValue> {
            val found = value.entries[key] ?: throw fail("missing data.$key")
            if (found !is JsonValue.Arr) throw fail("data.$key must be an array")
            return found.items
        }
        return BackupDataDraft(
            workouts = arr("workouts").mapIndexed { index, item -> decodeWorkout(item, index) },
            exercises = arr("exercises").mapIndexed { index, item -> decodeExercise(item, index) },
            executions = arr("executions").mapIndexed { index, item -> decodeExecution(item, index) },
            sets = arr("executionSets").mapIndexed { index, item -> decodeSet(item, index) }
        )
    }

    private fun fieldsOf(value: JsonValue, where: String): Map<String, JsonValue> {
        if (value !is JsonValue.Obj) throw fail("$where must be an object")
        return value.entries
    }

    private fun reqLong(fields: Map<String, JsonValue>, key: String, where: String): Long {
        val found = fields[key] ?: throw fail("$where: missing $key")
        return (found as? JsonValue.Num)?.longValue
            ?: throw fail("$where: $key must be an integer")
    }

    private fun reqInt(fields: Map<String, JsonValue>, key: String, where: String): Int {
        val value = reqLong(fields, key, where)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw fail("$where: $key out of range")
        return value.toInt()
    }

    private fun reqString(fields: Map<String, JsonValue>, key: String, where: String): String {
        val found = fields[key] ?: throw fail("$where: missing $key")
        return (found as? JsonValue.Str)?.value ?: throw fail("$where: $key must be a string")
    }

    private fun optIntField(fields: Map<String, JsonValue>, key: String, where: String): Int? {
        val found = fields[key] ?: return null
        if (found is JsonValue.Null) return null
        val value = (found as? JsonValue.Num)?.longValue ?: throw fail("$where: $key must be an integer or null")
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw fail("$where: $key out of range")
        return value.toInt()
    }

    private fun optLongField(fields: Map<String, JsonValue>, key: String, where: String): Long? {
        val found = fields[key] ?: return null
        if (found is JsonValue.Null) return null
        return (found as? JsonValue.Num)?.longValue ?: throw fail("$where: $key must be an integer or null")
    }

    private fun optDoubleField(fields: Map<String, JsonValue>, key: String, where: String): Double? {
        val found = fields[key] ?: return null
        if (found is JsonValue.Null) return null
        val num = (found as? JsonValue.Num) ?: throw fail("$where: $key must be a number or null")
        val value = num.doubleValue ?: num.longValue?.toDouble()
            ?: throw fail("$where: $key must be a number or null")
        if (!value.isFinite()) throw fail("$where: $key must be finite")
        return value
    }

    private fun decodeWorkout(value: JsonValue, index: Int): WorkoutEntity {
        val where = "workouts[$index]"
        val fields = fieldsOf(value, where)
        return WorkoutEntity(
            id = reqLong(fields, "id", where),
            code = reqString(fields, "code", where),
            name = reqString(fields, "name", where),
            createdAt = reqLong(fields, "createdAt", where)
        )
    }

    private fun decodeExercise(value: JsonValue, index: Int): ExerciseEntity {
        val where = "exercises[$index]"
        val fields = fieldsOf(value, where)
        val typeName = reqString(fields, "type", where)
        val type = try {
            ExerciseType.valueOf(typeName)
        } catch (_: IllegalArgumentException) {
            throw fail("$where: unknown type $typeName")
        }
        return ExerciseEntity(
            id = reqLong(fields, "id", where),
            workoutId = reqLong(fields, "workoutId", where),
            name = reqString(fields, "name", where),
            type = type,
            position = reqInt(fields, "position", where),
            restSeconds = reqInt(fields, "restSeconds", where),
            targetSets = optIntField(fields, "targetSets", where),
            targetReps = optIntField(fields, "targetReps", where),
            targetWeightKg = optDoubleField(fields, "targetWeightKg", where),
            targetDurationSeconds = optLongField(fields, "targetDurationSeconds", where),
            targetDistanceMeters = optDoubleField(fields, "targetDistanceMeters", where)
        )
    }

    private fun decodeExecution(value: JsonValue, index: Int): ExerciseExecutionEntity {
        val where = "executions[$index]"
        val fields = fieldsOf(value, where)
        return ExerciseExecutionEntity(
            id = reqLong(fields, "id", where),
            exerciseId = reqLong(fields, "exerciseId", where),
            performedAt = reqLong(fields, "performedAt", where),
            completedAt = optLongField(fields, "completedAt", where),
            durationSeconds = optLongField(fields, "durationSeconds", where),
            distanceMeters = optDoubleField(fields, "distanceMeters", where)
        )
    }

    private fun decodeSet(value: JsonValue, index: Int): ExecutionSetEntity {
        val where = "executionSets[$index]"
        val fields = fieldsOf(value, where)
        return ExecutionSetEntity(
            id = reqLong(fields, "id", where),
            executionId = reqLong(fields, "executionId", where),
            setNumber = reqInt(fields, "setNumber", where),
            reps = reqInt(fields, "reps", where),
            weightKg = optDoubleField(fields, "weightKg", where)
        )
    }

    private fun fail(reason: String): BackupDecodeException =
        BackupDecodeException(BackupDecodeError.Corrupt(reason))
}

class BackupDecodeException(val error: BackupDecodeError) : Exception(error.toString())
