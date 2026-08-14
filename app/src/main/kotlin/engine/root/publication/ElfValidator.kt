// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File
import java.security.MessageDigest

internal data class ElfIdentity(
    val abi: String,
    val sha256: String,
)

internal fun validateElfFile(
    path: String,
    supportedAbis: List<String>,
    expectedSha256: String? = null,
): ElfIdentity {
    val file = File(path)
    val abi = validateElfHeaderFile(path, supportedAbis)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    expectedSha256?.let { expected -> require(sha256.equals(expected, ignoreCase = true)) { "Core SHA-256 mismatch" } }
    return ElfIdentity(abi, sha256)
}

internal fun validateElfHeaderFile(
    path: String,
    supportedAbis: List<String>,
): String {
    val file = File(path)
    require(file.isFile && file.length() >= ElfHeaderMinimumSize) { "Core is missing or empty" }
    val header = file.inputStream().use { input ->
        val bytes = ByteArray(ElfHeaderReadSize)
        var length = 0
        while (length < bytes.size) {
            val count = input.read(bytes, length, bytes.size - length)
            if (count < 0) break
            if (count > 0) length += count
        }
        bytes.copyOf(length)
    }
    return supportedAbis.firstOrNull { candidate -> runCatching { validateElfHeader(header, candidate) }.isSuccess }
        ?: error("Core ELF class/machine does not match a supported device ABI")
}

internal fun validateElfHeader(header: ByteArray, abi: String) {
    require(header.size >= ElfHeaderMinimumSize)
    require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte())
    require(header[5].toInt() == ElfDataLittleEndian)
    val expected = when (abi) {
        "arm64-v8a" -> ElfClass64 to ElfMachineAarch64
        "armeabi-v7a" -> ElfClass32 to ElfMachineArm
        "x86" -> ElfClass32 to ElfMachineX86
        "x86_64" -> ElfClass64 to ElfMachineX8664
        else -> error("Unsupported Android ABI: $abi")
    }
    require(header[4].toInt() == expected.first)
    val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
    require(machine == expected.second)
}

private const val ElfHeaderMinimumSize = 20
private const val ElfHeaderReadSize = 64
private const val ElfClass32 = 1
private const val ElfClass64 = 2
private const val ElfDataLittleEndian = 1
private const val ElfMachineX86 = 3
private const val ElfMachineArm = 40
private const val ElfMachineX8664 = 62
private const val ElfMachineAarch64 = 183
