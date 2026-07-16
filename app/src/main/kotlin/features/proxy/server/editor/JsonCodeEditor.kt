// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Stable
internal class JsonCodeEditorState(
    initialText: String = "",
) {
    private var editor: CodeEditor? = null
    private var retainedText = initialText
    private var moveCursorToEnd = true

    var documentVersion by mutableIntStateOf(0)
        private set

    var isEmpty by mutableStateOf(initialText.isEmpty())
        private set

    var isFocused by mutableStateOf(false)
        private set

    internal fun attach(editor: CodeEditor) {
        if (this.editor === editor) return
        this.editor = editor
        applyRetainedText(editor)
    }

    internal fun detach(editor: CodeEditor) {
        if (this.editor !== editor) return
        retainedText = editor.text.toString()
        isEmpty = editor.text.length == 0
        isFocused = false
        this.editor = null
    }

    internal fun onContentChanged(editor: CodeEditor, action: Int) {
        if (this.editor !== editor || action == ContentChangeEvent.ACTION_SET_NEW_TEXT) return
        isEmpty = editor.text.length == 0
        documentVersion += 1
    }

    internal fun onFocusChanged(editor: CodeEditor, focused: Boolean) {
        if (this.editor === editor) {
            isFocused = focused
        }
    }

    fun snapshotText(): String = editor?.text?.toString() ?: retainedText

    fun replaceText(text: String, placeCursorAtEnd: Boolean = true) {
        if (text == retainedText && editor?.text?.toString() == text) return
        retainedText = text
        moveCursorToEnd = placeCursorAtEnd
        isEmpty = text.isEmpty()
        editor?.let(::applyRetainedText)
        documentVersion += 1
    }

    private fun applyRetainedText(editor: CodeEditor) {
        editor.setText(retainedText)
        if (moveCursorToEnd) {
            val lastLine = editor.lineCount - 1
            editor.setSelection(lastLine, editor.text.getColumnCount(lastLine), false)
        } else {
            editor.setSelection(0, 0, false)
        }
    }
}

@Composable
internal fun JsonCodeEditor(
    label: String,
    state: JsonCodeEditorState,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val colors = rememberJsonEditorColors()
    val colorScheme = remember(colors) { colors.toSoraColorScheme() }
    val shape = RoundedCornerShape(TextFieldDefaults.CornerRadius)
    val borderWidth by animateDpAsState(if (state.isFocused) FocusedBorderWidth else 0.dp)
    val borderColor by animateColorAsState(
        if (state.isFocused) colors.accent else colors.border,
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.background)
            .border(borderWidth, borderColor, shape),
    ) {
        AndroidView(
            factory = { context ->
                JsonSoraEditor(context).also { editor ->
                    editor.configure(colors, colorScheme)
                    editor.bindState(state)
                }
            },
            update = { editor ->
                editor.bindState(state)
                if (editor.colorScheme !== colorScheme) {
                    editor.colorScheme = colorScheme
                }
                editor.isEnabled = true
                editor.isFocusable = true
                editor.isFocusableInTouchMode = true
                editor.isEditable = !readOnly
            },
            onRelease = { editor ->
                editor.bindState(null)
                editor.release()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.isEmpty) {
            BasicText(
                text = label,
                style = MiuixTheme.textStyles.body2.copy(color = colors.placeholder),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = PlaceholderStartPadding, top = PlaceholderTopPadding),
            )
        }
    }
}

private class JsonSoraEditor(context: Context) : CodeEditor(context) {
    private var boundState: JsonCodeEditorState? = null

    init {
        subscribeAlways(ContentChangeEvent::class.java) { event ->
            boundState?.onContentChanged(this, event.action)
        }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            boundState?.onFocusChanged(this, focused)
        }
    }

    fun bindState(state: JsonCodeEditorState?) {
        if (boundState === state) return
        boundState?.detach(this)
        boundState = state
        state?.attach(this)
    }
}

private fun JsonSoraEditor.configure(
    colors: JsonEditorColors,
    colorScheme: EditorColorScheme,
) {
    setTextSize(EditorTextSize)
    setTypefaceText(Typeface.MONOSPACE)
    setTypefaceLineNumber(Typeface.MONOSPACE)
    setLineSpacing(dp(EditorLineSpacing).toFloat(), 1f)
    setTabWidth(EditorTabWidth)
    setWordwrap(false)
    setPinLineNumber(true)
    setLineNumberEnabled(true)
    setLineNumberMarginLeft(dp(LineNumberMargin).toFloat())
    setDividerWidth(dp(DividerWidth).toFloat())
    setHighlightCurrentBlock(false)
    setBlockLineEnabled(false)
    setRenderFunctionCharacters(false)
    setLigatureEnabled(false)
    setScalable(true)
    setCursorAnimationEnabled(true)
    setInputType(
        InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
    )
    setPadding(0, dp(EditorVerticalPadding), dp(EditorHorizontalPadding), dp(EditorVerticalPadding))
    setEdgeEffectColor(colors.accent.toArgb())
    this.colorScheme = colorScheme
    setEditorLanguage(JsonSoraLanguage())
}

private fun CodeEditor.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

private class JsonSoraLanguage : EmptyLanguage() {
    private val analyzer = JsonIncrementalAnalyzeManager()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer
}

private class JsonIncrementalAnalyzeManager : AsyncIncrementalAnalyzeManager<Unit, JsonToken>(true) {
    override fun getInitialState() = Unit

    override fun stateEquals(state: Unit, another: Unit): Boolean = true

    override fun tokenizeLine(
        line: CharSequence,
        state: Unit,
        lineIndex: Int,
    ): LineTokenizeResult<Unit, JsonToken> {
        return LineTokenizeResult(Unit, tokenizeJsonLine(line))
    }

    override fun generateSpansForLine(tokens: LineTokenizeResult<Unit, JsonToken>): List<Span> {
        val lineTokens = tokens.tokens.orEmpty()
        if (lineTokens.isEmpty()) {
            return listOf(SpanFactory.obtainNoExt(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
        }
        return lineTokens.map { token ->
            SpanFactory.obtainNoExt(token.start, TextStyle.makeStyle(token.kind.soraColorId()))
        }
    }

    override fun computeBlocks(
        text: Content,
        delegate: CodeBlockAnalyzeDelegate,
    ): List<CodeBlock> = emptyList()
}

private fun JsonTokenKind.soraColorId(): Int = when (this) {
    JsonTokenKind.Normal -> EditorColorScheme.TEXT_NORMAL
    JsonTokenKind.Key -> EditorColorScheme.ATTRIBUTE_NAME
    JsonTokenKind.String -> EditorColorScheme.LITERAL
    JsonTokenKind.Number -> SyntaxNumberColorId
    JsonTokenKind.Literal -> SyntaxLiteralColorId
    JsonTokenKind.Punctuation -> EditorColorScheme.OPERATOR
}

private fun JsonEditorColors.toSoraColorScheme(): EditorColorScheme {
    return object : EditorColorScheme(darkTheme) {}.apply {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, background.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, gutter.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, lineNumber.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, accent.toArgb())
        setColor(EditorColorScheme.LINE_DIVIDER, separator.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, foreground.toArgb())
        setColor(EditorColorScheme.SELECTION_INSERT, accent.toArgb())
        setColor(EditorColorScheme.SELECTION_HANDLE, accent.toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection.toArgb())
        setColor(EditorColorScheme.CURRENT_LINE, currentLine.toArgb())
        setColor(EditorColorScheme.ATTRIBUTE_NAME, syntax.key.toArgb())
        setColor(EditorColorScheme.LITERAL, syntax.string.toArgb())
        setColor(SyntaxNumberColorId, syntax.number.toArgb())
        setColor(SyntaxLiteralColorId, syntax.literal.toArgb())
        setColor(EditorColorScheme.OPERATOR, syntax.punctuation.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_NAME, foreground.toArgb())
    }
}

private const val SyntaxNumberColorId = 256
private const val SyntaxLiteralColorId = 257
private const val EditorTextSize = 14f
private const val EditorLineSpacing = 4f
private const val EditorVerticalPadding = 8f
private const val EditorHorizontalPadding = 10f
private const val EditorTabWidth = 2
private const val LineNumberMargin = 4f
private const val DividerWidth = 1f
private val FocusedBorderWidth = 2.dp
private val PlaceholderStartPadding = 50.dp
private val PlaceholderTopPadding = 9.dp
