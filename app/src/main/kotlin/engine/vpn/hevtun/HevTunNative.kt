// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn.hevtun

import androidx.annotation.Keep

internal interface HevTunNativeGateway {
    fun startService(configPath: String, fd: Int): Boolean
    fun stopService(): Boolean
    fun isRunning(): Boolean
}

@Keep
internal object HevTunNative : HevTunNativeGateway {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    @Suppress("FunctionName")
    private external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    private external fun TProxyStopService(): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    private external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Keep
    @Suppress("FunctionName")
    private external fun TProxyGetStats(): LongArray

    override fun startService(configPath: String, fd: Int): Boolean {
        return TProxyStartService(configPath, fd)
    }

    override fun stopService(): Boolean {
        return TProxyStopService()
    }

    override fun isRunning(): Boolean {
        return TProxyIsRunning()
    }
}
