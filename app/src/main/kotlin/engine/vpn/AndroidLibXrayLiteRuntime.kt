// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import app.R
import features.logs.AndroidAppLogger
import engine.xray.initializeAndroidXrayCoreEnvironment
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

internal object AndroidLibXrayLiteRuntime {
    private val controllerLock = Any()
    private var processFinderOwner: CoreController? = null
    @Volatile
    private var coreController: CoreController? = null

    fun start(
        context: Context,
        config: VpnServiceStartConfig,
        tunFd: Int,
    ) {
        require(config.dataDir.isNotBlank()) {
            context.getString(R.string.error_android_lib_xray_lite_data_dir_missing)
        }
        context.initializeAndroidXrayCoreEnvironment(config.dataDir)
        val controller = Libv2ray.newCoreController(AndroidLibXrayLiteCallbackHandler())
        runCatching {
            synchronized(controllerLock) {
                controller.registerProcessFinder(AndroidXrayProcessFinder(context))
                processFinderOwner = controller
            }
            controller.startLoop(context.resolveVpnXrayProcessRules(config.xrayConfigJson), tunFd)
        }.onFailure { error ->
            runCatching { controller.stopLoop() }
                .onFailure { stopError ->
                    AndroidAppLogger.warn(LogTag, "Failed to stop AndroidLibXrayLite after start failure", stopError)
                }
            controller.clearProcessFinder()
            throw IllegalStateException(
                context.getString(R.string.error_android_lib_xray_lite_start_failed, error.readableMessage()),
                error,
            )
        }
        synchronized(controllerLock) {
            coreController = controller
        }
    }

    fun stop() {
        val controller = coreController ?: return
        runCatching {
            controller.stopLoop()
        }.onFailure { error ->
            AndroidAppLogger.error(LogTag, "Failed to stop AndroidLibXrayLite", error)
        }
        controller.clearProcessFinder()
        synchronized(controllerLock) {
            if (coreController === controller) coreController = null
        }
    }

    fun isRunning(): Boolean {
        return coreController?.isRunning == true
    }

    private fun CoreController.clearProcessFinder() {
        synchronized(controllerLock) {
            // Native shutdown may outlive the service's bounded wait. The Go finder is global,
            // so an old controller must never unregister a replacement controller's finder.
            if (processFinderOwner !== this) return
            runCatching { registerProcessFinder(null) }
                .onFailure { error ->
                    AndroidAppLogger.warn(LogTag, "Failed to unregister Android process finder", error)
                }
            processFinderOwner = null
        }
    }

    private const val LogTag = "AndroidLibXrayLite"
}

private class AndroidLibXrayLiteCallbackHandler : CoreCallbackHandler {
    override fun startup(): Long {
        AndroidAppLogger.info("AndroidLibXrayLite", "AndroidLibXrayLite started")
        return 0
    }

    override fun shutdown(): Long {
        AndroidAppLogger.info("AndroidLibXrayLite", "AndroidLibXrayLite stopped")
        return 0
    }

    override fun onEmitStatus(code: Long, message: String?): Long {
        val text = message.orEmpty().ifBlank { "status code: $code" }
        AndroidAppLogger.info("AndroidLibXrayLite", text)
        return 0
    }
}

private fun Throwable.readableMessage(): String {
    return message ?: javaClass.simpleName.orEmpty()
}
