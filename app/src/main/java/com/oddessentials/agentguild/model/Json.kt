package com.oddessentials.agentguild.model

/**
 * A strict JSON reader (RFC 8259) and string writer. Android's org.json accepts comments, single
 * quotes and unquoted names, and is missing from JVM tests; the bridge and the store want neither.
 * Objects read as maps, arrays as lists, numbers as doubles. A repeated name makes an object
 * ambiguous, so it is refused.
 */
object Json {
    class SyntaxException(message: String) : Exception(message)

    /** The value [text] holds, or null when it isn't JSON. A JSON `null` also reads as null. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: SyntaxException) {
        null
    }

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd) throw SyntaxException("text after the value")
        return value
    }

    fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0
        val atEnd get() = index == text.length

        fun skipSpace() {
            while (index < text.length && text[index] in " \t\n\r") index++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw SyntaxException("nested too deeply")
            if (atEnd) throw SyntaxException("missing value")
            return when (text[index]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw SyntaxException("expected a name")
                val name = string()
                skipSpace()
                expect(':')
                skipSpace()
                if (result.containsKey(name)) throw SyntaxException("repeated name")
                result[name] = value(depth + 1)
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw SyntaxException("expected , or }")
                }
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipSpace()
                result.add(value(depth + 1))
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw SyntaxException("expected , or ]")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> when (next()) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) throw SyntaxException("short escape")
                            val hex = text.substring(index, index + 4)
                            if (!hex.all { it in "0123456789abcdefABCDEF" }) throw SyntaxException("bad escape")
                            result.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> throw SyntaxException("bad escape")
                    }
                    char < ' ' -> throw SyntaxException("control character in a string")
                    else -> result.append(char)
                }
            }
        }

        private fun number(): Double {
            val match = NUMBER.matchAt(text, index) ?: throw SyntaxException("unexpected character")
            index = match.range.last + 1
            return match.value.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) throw SyntaxException("unexpected word")
            index += word.length
            return value
        }

        private fun peek(): Char? = if (atEnd) null else text[index]

        private fun next(): Char {
            if (atEnd) throw SyntaxException("unexpected end")
            return text[index++]
        }

        private fun expect(char: Char) {
            if (next() != char) throw SyntaxException("expected $char")
        }
    }

    private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
}
