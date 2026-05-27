package features.proxy.server.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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
            accent = primary,
            foreground = foreground,
            background = background,
            gutter = enhancedThemeColor(primaryHue, darkTheme),
            separator = primary.copy(alpha = if (darkTheme) 0.24f else 0.18f),
            border = primary.copy(alpha = if (darkTheme) 0.20f else 0.14f),
            lineNumber = onSurfaceVariantSummary.copy(alpha = if (darkTheme) 0.78f else 0.68f),
            placeholder = onSecondaryContainer.copy(alpha = if (darkTheme) 0.70f else 0.58f),
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

@Composable
internal fun rememberJsonSyntaxHighlightTransformation(
    colors: JsonEditorColors,
): VisualTransformation {
    val syntaxColors = colors.syntax
    return remember(syntaxColors) {
        JsonSyntaxHighlightTransformation(syntaxColors)
    }
}

internal data class JsonEditorColors(
    val accent: Color,
    val foreground: Color,
    val background: Color,
    val gutter: Color,
    val separator: Color,
    val border: Color,
    val lineNumber: Color,
    val placeholder: Color,
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

private class JsonSyntaxHighlightTransformation(
    private val colors: JsonSyntaxColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(highlightJson(text.text, colors), OffsetMapping.Identity)
    }
}

private fun highlightJson(
    text: String,
    colors: JsonSyntaxColors,
): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    var index = 0
    while (index < text.length) {
        when (val char = text[index]) {
            '"' -> {
                val end = text.stringTokenEnd(index)
                val tokenColor = if (text.isObjectKey(end)) colors.key else colors.string
                builder.addStyle(SpanStyle(color = tokenColor), index, end)
                index = end
            }

            '-', in '0'..'9' -> {
                val end = text.numberTokenEnd(index)
                builder.addStyle(SpanStyle(color = colors.number), index, end)
                index = end
            }

            't', 'f', 'n' -> {
                val end = text.literalTokenEnd(index)
                if (end > index) {
                    builder.addStyle(SpanStyle(color = colors.literal), index, end)
                    index = end
                } else {
                    index += 1
                }
            }

            '{', '}', '[', ']', ':', ',' -> {
                builder.addStyle(SpanStyle(color = colors.punctuation), index, index + 1)
                index += 1
            }

            else -> index += 1
        }
    }
    return builder.toAnnotatedString()
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

private val JsonNumberTokenChars = setOf('-', '+', '.', 'e', 'E') + ('0'..'9')
private val JsonLiterals = listOf("true", "false", "null")

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
