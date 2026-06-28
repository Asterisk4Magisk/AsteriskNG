// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import kotlin.io.encoding.Base64

internal fun ByteArray.encodeBase64(): String {
    return Base64.encode(this)
}

internal fun String.encodeBase64(): String {
    return toByteArray(Charsets.UTF_8).encodeBase64()
}

internal fun String.decodeBase64OrNull(): ByteArray? {
    return runCatching {
        Base64.decode(this)
    }.getOrNull()
}

internal fun String.decodeBase64StringOrNull(): String? {
    return decodeBase64OrNull()?.toString(Charsets.UTF_8)
}

internal fun ByteArray.encodeUrlSafeBase64NoPadding(): String {
    return Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(this)
}

internal fun ByteArray.encodeUrlSafeBase64OptionalPadding(): String {
    return Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .encode(this)
}

internal fun String.decodeUrlSafeBase64OptionalPaddingOrNull(): ByteArray? {
    return runCatching {
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            .decode(this)
    }.getOrNull()
}

internal fun String.decodeUrlSafeBase64NoPaddingOrNull(): ByteArray? {
    return runCatching {
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .decode(this)
    }.getOrNull()
}

internal fun String.decodeFlexibleBase64OrNull(): ByteArray? {
    val normalized = filterNot(Char::isWhitespace)
    if (normalized.isBlank()) return null
    return FlexibleBase64Decoders.firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(normalized) }.getOrNull()
    } ?: normalized.trimEnd('=').takeIf { it.length != normalized.length }?.let { trimmed ->
        FlexibleBase64Decoders.firstNotNullOfOrNull { decoder ->
            runCatching { decoder.decode(trimmed) }.getOrNull()
        }
    }
}

private val FlexibleBase64Decoders = listOf(
    Base64.Default,
    Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
    Base64.UrlSafe,
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
)
