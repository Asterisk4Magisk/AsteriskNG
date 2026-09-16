// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

/**
 * Redacts private filesystem locations from diagnostic text that is displayed to the user and
 * copied into a bug report.
 *
 * The project rules require errors to stay diagnosable while never exposing configuration
 * content, key material, or private arguments. App-data and shared-storage paths are exactly
 * that, and they do reach the report: the service log records absolute paths, for instance the
 * SingBox log file that could not be cleared, and the dialog's copy action is meant to be pasted
 * into a public issue.
 *
 * The final path segment is kept. A bare filename is diagnostically useful — it says which file
 * the app was working with — while revealing nothing about the surrounding directory layout.
 */
internal object DiagnosticRedaction {
    private const val Placeholder = "<path>"

    /**
     * A path under one of the private roots the app writes to.
     *
     * Anchored on those roots on purpose: it leaves unrelated text alone, so the actionable
     * `FATAL[...]` signature and relative section names such as `inbound/ebpf[inbound_root]` in
     * the same line survive untouched. Trailing punctuation is excluded so a path at the end of a
     * sentence does not swallow the separator after it.
     */
    private val privatePath = Regex("""(?:/data|/storage|/sdcard|/mnt)(?:/[^\s"',;)\]}>]*)+""")
    private val url = Regex("""(?i)\b(?:https?|ss|ssr|vmess|vless|trojan|hysteria2?|tuic|wireguard|socks5?)://[^\s"'<>]+""")
    private val secret = Regex(
        """(?i)(["']?(?:password|passwd|token|secret|private[-_]?key|uuid|authorization|age[-_]?secret[-_]?key)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)""",
    )
    private val ageKey = Regex("""AGE-SECRET-KEY-[A-Z0-9]+""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val sanitized = ageKey.replace(secret.replace(url.replace(text, "<url>")) {
            it.groupValues[1] + "<redacted>"
        }, "<redacted>")
        return privatePath.replace(sanitized) { match ->
            val name = match.value.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) Placeholder else "$Placeholder/$name"
        }
    }
}
