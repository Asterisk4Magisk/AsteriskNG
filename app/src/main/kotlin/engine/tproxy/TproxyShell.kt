package engine.tproxy

internal fun StringBuilder.appendScript(script: String) {
    append(script.trimIndent())
    append('\n')
}

internal fun StringBuilder.appendHeredoc(
    targetPath: String,
    delimiter: String,
    content: String,
) {
    appendScript("cat > ${targetPath.shellQuote()} <<'$delimiter'")
    append(content)
    if (!content.endsWith('\n')) {
        append('\n')
    }
    appendScript(delimiter)
}

internal fun String.shellQuote(): String {
    return "'${replace("'", "'\"'\"'")}'"
}

internal fun String.shellQuoteForCase(): String {
    return replace("\\", "\\\\")
        .replace("'", "'\"'\"'")
        .replace("*", "\\*")
        .replace("?", "\\?")
        .replace("[", "\\[")
}
