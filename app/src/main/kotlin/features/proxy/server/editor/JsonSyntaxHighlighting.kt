// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.isInDarkTheme

@Composable
internal fun rememberJsonEditorColors(): JsonEditorColors {
    val colorScheme = MiuixTheme.colorScheme
    val primary = colorScheme.primary
    val background = colorScheme.secondaryContainer
    val foreground = colorScheme.onSurface
    val darkTheme = isInDarkTheme()
    val onSurfaceVariantSummary = colorScheme.onSurfaceVariantSummary
    val onSecondaryContainer = colorScheme.onSecondaryContainer
    return remember(primary, background, foreground, darkTheme, onSurfaceVariantSummary, onSecondaryContainer) {
        val primaryHue = primary.hue()
        JsonEditorColors(
            darkTheme = darkTheme,
            accent = primary,
            foreground = foreground,
            background = background,
            gutter = enhancedThemeColor(primaryHue, darkTheme),
            separator = primary.copy(alpha = if (darkTheme) 0.24f else 0.18f),
            border = primary.copy(alpha = if (darkTheme) 0.20f else 0.14f),
            lineNumber = onSurfaceVariantSummary.copy(alpha = if (darkTheme) 0.78f else 0.68f),
            placeholder = onSecondaryContainer.copy(alpha = if (darkTheme) 0.70f else 0.58f),
            selection = primary.copy(alpha = if (darkTheme) 0.34f else 0.24f),
            currentLine = primary.copy(alpha = if (darkTheme) 0.10f else 0.06f),
            formatButtonBackground = primary.copy(alpha = if (darkTheme) 0.18f else 0.14f),
            syntax = JsonSyntaxColors(
                key = vividThemeColor(primaryHue, hueOffset = 0f, darkTheme = darkTheme),
                string = vividThemeColor(primaryHue, hueOffset = 88f, darkTheme = darkTheme),
                number = vividThemeColor(primaryHue, hueOffset = -52f, darkTheme = darkTheme),
                literal = vividThemeColor(primaryHue, hueOffset = 176f, darkTheme = darkTheme),
                punctuation = onSurfaceVariantSummary,
            ),
        )
    }
}

internal data class JsonEditorColors(
    val darkTheme: Boolean,
    val accent: Color,
    val foreground: Color,
    val background: Color,
    val gutter: Color,
    val separator: Color,
    val border: Color,
    val lineNumber: Color,
    val placeholder: Color,
    val selection: Color,
    val currentLine: Color,
    val formatButtonBackground: Color,
    val syntax: JsonSyntaxColors,
)

internal data class JsonSyntaxColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val literal: Color,
    val punctuation: Color,
)

internal enum class JsonTokenKind {
    Normal,
    Key,
    String,
    Number,
    Literal,
    Punctuation,
}

internal data class JsonToken(
    val start: Int,
    val kind: JsonTokenKind,
)

internal fun tokenizeJsonLine(line: CharSequence): List<JsonToken> {
    val text = line.toString()
    val tokens = mutableListOf<JsonToken>()
    var index = 0
    while (index < text.length) {
        val start = index
        when (text[index]) {
            '"' -> {
                index = text.stringTokenEnd(index)
                tokens += JsonToken(
                    start = start,
                    kind = if (text.isObjectKey(index)) JsonTokenKind.Key else JsonTokenKind.String,
                )
            }

            '-', in '0'..'9' -> {
                index = text.numberTokenEnd(index)
                tokens += JsonToken(start, JsonTokenKind.Number)
            }

            't', 'f', 'n' -> {
                val end = text.literalTokenEnd(index)
                if (end > index) {
                    index = end
                    tokens += JsonToken(start, JsonTokenKind.Literal)
                } else {
                    index += 1
                    tokens += JsonToken(start, JsonTokenKind.Normal)
                }
            }

            '{', '}', '[', ']', ':', ',' -> {
                index += 1
                tokens += JsonToken(start, JsonTokenKind.Punctuation)
            }

            else -> {
                index += 1
                while (index < text.length && !text.isJsonTokenStart(index)) {
                    index += 1
                }
                tokens += JsonToken(start, JsonTokenKind.Normal)
            }
        }
    }
    return tokens.ifEmpty { listOf(JsonToken(0, JsonTokenKind.Normal)) }
}

private fun String.isJsonTokenStart(index: Int): Boolean {
    val char = this[index]
    return char == '"' ||
        char == '-' ||
        char.isDigit() ||
        char in JsonLiteralStarts ||
        char in JsonPunctuation
}

private fun String.stringTokenEnd(start: Int): Int {
    var index = start + 1
    var escaped = false
    while (index < length) {
        val char = this[index]
        if (escaped) {
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else if (char == '"') {
            return index + 1
        }
        index += 1
    }
    return length
}

private fun String.isObjectKey(stringEnd: Int): Boolean {
    var index = stringEnd
    while (index < length && this[index].isWhitespace()) {
        index += 1
    }
    return index < length && this[index] == ':'
}

private fun String.numberTokenEnd(start: Int): Int {
    var index = start
    while (index < length && this[index] in JsonNumberTokenChars) {
        index += 1
    }
    return index
}

private fun String.literalTokenEnd(start: Int): Int {
    val literal = JsonLiterals.firstOrNull { literal ->
        startsWith(literal, startIndex = start)
    } ?: return start
    val end = start + literal.length
    val boundaryBefore = start == 0 || !this[start - 1].isLetter()
    val boundaryAfter = end == length || !this[end].isLetter()
    return if (boundaryBefore && boundaryAfter) end else start
}

private fun Color.hue(): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv[0]
}

private fun vividThemeColor(
    baseHue: Float,
    hueOffset: Float,
    darkTheme: Boolean,
): Color {
    val hue = (baseHue + hueOffset).floorMod(360f)
    val saturation = if (darkTheme) 0.78f else 0.86f
    val value = if (darkTheme) 0.96f else 0.70f
    return Color.hsv(hue = hue, saturation = saturation, value = value)
}

private fun enhancedThemeColor(
    baseHue: Float,
    darkTheme: Boolean,
): Color {
    val saturation = if (darkTheme) 0.24f else 0.20f
    val value = if (darkTheme) 0.26f else 0.96f
    return Color.hsv(hue = (baseHue + 10f).floorMod(360f), saturation = saturation, value = value)
}

private fun Float.floorMod(modulus: Float): Float {
    val result = this % modulus
    return if (result < 0f) result + modulus else result
}

private val JsonNumberTokenChars = setOf('-', '+', '.', 'e', 'E') + ('0'..'9')
private val JsonLiterals = listOf("true", "false", "null")
private val JsonLiteralStarts = setOf('t', 'f', 'n')
private val JsonPunctuation = setOf('{', '}', '[', ']', ':', ',')
