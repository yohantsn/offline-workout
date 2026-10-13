package com.offlineworkout.data.backup

// T8.1: minimal JSON value model + recursive-descent parser + canonical
// writer. Hand-rolled (no serialization dependency) so export bytes are
// byte-stable: fixed key order, no insignificant whitespace, stable numbers.
// Pure Kotlin, no Android imports — unit-testable on plain JVM.
sealed interface JsonValue {
    data class Obj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val longValue: Long?, val doubleValue: Double?) : JsonValue {
        companion object {
            fun ofLong(value: Long) = Num(value, null)
            fun ofDouble(value: Double) = Num(null, value)
        }
    }
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class JsonParseException(message: String) : Exception(message)

object BackupJsonParser {

    // T8.3: depth is bounded so a hostile document full of nested brackets
    // fails as a parse error instead of a StackOverflowError.
    fun parse(text: String, maxDepth: Int = MAX_JSON_DEPTH): JsonValue {
        val parser = Parser(text, maxDepth)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("trailing content")
        return value
    }

    private class Parser(val text: String, val maxDepth: Int) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[pos] in " \t\n\r") pos++
        }

        fun parseValue(depth: Int): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end")
            if (depth > maxDepth) throw JsonParseException("nesting too deep")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("unexpected '$c'")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            pos++ // {
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw JsonParseException("expected string key")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw JsonParseException("expected ':'")
                pos++
                entries[key] = parseValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw JsonParseException("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            pos++ // [
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonParseException("bad unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("bad unicode escape")
                                pos += 4
                                out.append(code.toChar())
                            }
                            else -> throw JsonParseException("bad escape")
                        }
                    }
                    else -> {
                        if (c < ' ') throw JsonParseException("unescaped control character")
                        out.append(c)
                    }
                }
            }
        }

        private fun parseLiteral(keyword: String, value: JsonValue): JsonValue {
            if (!text.startsWith(keyword, pos)) throw JsonParseException("invalid literal")
            pos += keyword.length
            return value
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            if (atEnd()) throw JsonParseException("bad number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (!atEnd() && text[pos] in '0'..'9') pos++
            } else {
                throw JsonParseException("bad number")
            }
            var isDouble = false
            if (!atEnd() && text[pos] == '.') {
                isDouble = true
                pos++
                if (atEnd() || text[pos] !in '0'..'9') throw JsonParseException("bad number")
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') throw JsonParseException("bad number")
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            return if (!isDouble) {
                JsonValue.Num.ofLong(raw.toLongOrNull() ?: throw JsonParseException("integer out of range"))
            } else {
                val parsed = raw.toDoubleOrNull() ?: throw JsonParseException("bad number")
                if (!parsed.isFinite()) throw JsonParseException("non-finite number")
                JsonValue.Num.ofDouble(parsed)
            }
        }
    }
}

object BackupJsonWriter {

    fun write(value: JsonValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                append('{')
                value.entries.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is JsonValue.Arr -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonValue.Str -> appendString(value.value)
            is JsonValue.Num -> {
                if (value.longValue != null) append(value.longValue.toString())
                else append(value.doubleValue.toString())
            }
            is JsonValue.Bool -> append(if (value.value) "true" else "false")
            is JsonValue.Null -> append("null")
        }
    }

    private fun StringBuilder.appendString(raw: String) {
        append('"')
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
