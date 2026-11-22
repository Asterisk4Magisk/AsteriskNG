// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.security.MessageDigest

internal fun ByteArray.sha256Digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

internal fun ByteArray.sha256Hex(): String = sha256Digest().toHexString()

internal fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
