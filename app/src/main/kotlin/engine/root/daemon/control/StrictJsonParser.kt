// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseClosedPayload(payload: String): JsonObject {
    require(payload.toByteArray(Charsets.UTF_8).size <= MaxControlPayloadBytes)
    require(payload.isNotEmpty() && '\u0000' !in payload && '\n' !in payload && '\r' !in payload)
    DuplicateKeyParser(payload).validate()
    return ControlJson.parseToJsonElement(payload).jsonObject
}

private class DuplicateKeyParser(private val source: String) {
    private var offset = 0

    fun validate() {
        parseValue(0)
        skipWhitespace()
        require(offset == source.length)
    }

    private fun parseValue(depth: Int) {
        require(depth <= MaxJsonDepth)
        skipWhitespace()
        require(offset < source.length)
        when (source[offset]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> parseString()
            else -> parsePrimitive()
        }
    }

    private fun parseObject(depth: Int) {
        ++offset
        skipWhitespace()
        val keys = mutableSetOf<String>()
        if (consume('}')) return
        while (true) {
            skipWhitespace()
            require(offset < source.length && source[offset] == '"')
            val key = parseString()
            require(keys.add(key)) { "Duplicate JSON key" }
            skipWhitespace()
            require(consume(':'))
            parseValue(depth)
            skipWhitespace()
            if (consume('}')) return
            require(consume(','))
        }
    }

    private fun parseArray(depth: Int) {
        ++offset
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue(depth)
            skipWhitespace()
            if (consume(']')) return
            require(consume(','))
        }
    }

    private fun parseString(): String {
        val start = offset
        require(consume('"'))
        while (offset < source.length) {
            when (source[offset++]) {
                '"' -> return ControlJson.parseToJsonElement(source.substring(start, offset)).jsonPrimitive.content
                '\\' -> require(offset < source.length).also { ++offset }
            }
        }
        error("Unterminated JSON string")
    }

    private fun parsePrimitive() {
        val start = offset
        while (offset < source.length && source[offset] !in PrimitiveDelimiters) ++offset
        require(offset > start)
    }

    private fun skipWhitespace() {
        while (offset < source.length && source[offset] in JsonWhitespace) ++offset
    }

    private fun consume(character: Char): Boolean {
        if (offset >= source.length || source[offset] != character) return false
        ++offset
        return true
    }
}

private val ControlJson = Json
private val JsonWhitespace = charArrayOf(' ', '\t', '\n', '\r')
private val PrimitiveDelimiters = charArrayOf(' ', '\t', '\n', '\r', ',', ']', '}')
private const val MaxControlPayloadBytes = 65536
private const val MaxJsonDepth = 64
