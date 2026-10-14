// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolve a display name for a subscription group from a remote HTTP response.
 *
 * Resolution order:
 *
 * 1. [preferredName] — the already-decoded `Content-Disposition` filename the
 *    server supplied. NekoBox, husi and FlClash all install this hook, and
 *    subscription platforms populate the filename with the subscription's real
 *    title (e.g. `雪山 Link`), typically only for clients whose User-Agent they
 *    recognise. This is the only path that can yield a human title.
 * 2. The subscription URL's host, with a leading `www.` stripped.
 *
 * There is deliberately no random fallback. A name the user cannot recognise
 * is worse than no name at all, and callers treat `null` as "leave the title
 * alone".
 *
 * [formatDuplicate] builds the collision suffix; callers pass the string
 * resource so the punctuation can be localized. See [disambiguateName].
 *
 * Callers must only invoke this when the user has not already supplied a name;
 * see `withUpdatedSubscriptionServers`.
 */
internal fun resolveSubscriptionName(
    preferredName: String?,
    subscriptionUrl: String,
    takenNames: Collection<String>,
    formatDuplicate: (name: String, ordinal: Int) -> String,
): String? {
    val base = preferredName?.trim()?.takeIf(String::isNotBlank)
        ?: nameFromSubscriptionUrl(subscriptionUrl)
        ?: return null
    return disambiguateName(base, takenNames, formatDuplicate)
}

/**
 * Append the [formatDuplicate] suffix (`%1$s(%2$d)` in the shipped locale files)
 * to [base] until it no longer collides with [takenNames]. Mirrors FlClash's
 * `getOverwriteLabel`, so a duplicated subscription title reads the same here
 * as it does there.
 *
 * The suffix is built by the caller rather than with a local literal because
 * the result is user-visible and gets persisted as the group's title.
 */
internal fun disambiguateName(
    base: String,
    takenNames: Collection<String>,
    formatDuplicate: (name: String, ordinal: Int) -> String,
): String {
    val taken = takenNames.mapTo(mutableSetOf(), String::trim)
    if (base !in taken) return base
    var ordinal = 1
    while (true) {
        val candidate = formatDuplicate(base, ordinal)
        if (candidate !in taken) return candidate
        ordinal += 1
    }
}

/**
 * Derive a label from a subscription URL: the host, minus a leading `www.`.
 *
 * Returns `null` when the URL has no parseable host, or when it uses a
 * non-network scheme. The latter matters because `content://`-style sources
 * (local files) do parse with a "host" — `content://media/external/file/1`
 * yields `media` — and that is not a domain.
 */
internal fun nameFromSubscriptionUrl(rawUrl: String): String? {
    val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase()
    if (scheme != null && scheme != "http" && scheme != "https") return null
    val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
    return host.removePrefix("www.").ifBlank { host }
}

/**
 * Extract a human-readable filename from a `Content-Disposition` header value.
 *
 * Supports the two formats servers actually emit in the wild:
 *
 * 1. RFC 5987 / RFC 6266 extended form (always preferred when both are present):
 *    `attachment; filename="fallback.txt"; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt`
 * 2. Bare `filename="..."` form, with optional surrounding whitespace and any
 *    leading `attachment;`/`inline;`/`form-data;` disp-type token. The bare
 *    form is non-conformant but is what many small subscription endpoints
 *    actually return, so we accept it rather than silently dropping the name.
 *
 * Returns `null` when the header is missing, malformed, or yields an empty
 * filename after decoding — callers should fall back to the URL-derived name.
 */
internal fun decodeContentDispositionFilename(headerValue: String?): String? {
    val raw = headerValue?.trim().orEmpty()
    if (raw.isEmpty()) return null

    // 1) Prefer RFC 5987 extended form: filename*=UTF-8''<percent-encoded>
    val extended = FilenameExtendedRegex.find(raw)
    if (extended != null) {
        val decoded = decodePercent(extended.groupValues[1])
        if (decoded.isNotBlank()) return decoded.trim()
    }

    // 2) Bare `filename="..."` or `filename=...` token. The spec forbids
    //    bare forms but they are widespread; support them as a fallback.
    val bare = FilenameBareRegex.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (bare.isNotEmpty()) {
        val cleaned = bare.trim('"', '\'')
        if (cleaned.isNotBlank()) return cleaned
    }
    return null
}

private fun decodePercent(value: String): String =
    runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

private val FilenameExtendedRegex = Regex("""filename\*=(?:UTF-8|utf-8)''([^;]+)""")
private val FilenameBareRegex = Regex("""filename\s*=\s*("([^"]*)"|([^;]+))""", RegexOption.IGNORE_CASE)
