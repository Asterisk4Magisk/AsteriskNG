// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import libv2ray.ProcessFinder
import system.getApplicationInfoCompat
import java.net.InetSocketAddress

internal class AndroidXrayProcessFinder(context: Context) : ProcessFinder {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val packageManager = context.applicationContext.packageManager

    override fun findUIDByPackageName(packageName: String): Long {
        return try {
            packageManager.getApplicationInfoCompat(packageName).uid.toLong()
        } catch (_: PackageManager.NameNotFoundException) {
            InvalidUid
        }
    }

    override fun findProcessByConnection(
        network: String,
        srcIP: String,
        srcPort: Long,
        destIP: String,
        destPort: Long,
    ): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return InvalidUid
        val manager = connectivityManager ?: return InvalidUid
        val protocol = when (network) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return InvalidUid
        }
        if (srcIP.isBlank() || destIP.isBlank() || srcPort !in 1L..65535L || destPort !in 1L..65535L) {
            return InvalidUid
        }
        return try {
            manager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(srcIP, srcPort.toInt()),
                InetSocketAddress(destIP, destPort.toInt()),
            ).toLong()
        } catch (_: Exception) {
            // Connections may disappear, or VPN permission may be revoked during lookup.
            InvalidUid
        }
    }

    private companion object {
        const val InvalidUid = -1L
    }
}
