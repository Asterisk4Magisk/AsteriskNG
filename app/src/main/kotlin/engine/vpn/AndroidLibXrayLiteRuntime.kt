package engine.vpn

import android.content.Context
import app.R
import features.logs.AndroidAppLogger
import engine.xray.initializeAndroidXrayCoreEnvironment
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

internal object AndroidLibXrayLiteRuntime {
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
            controller.startLoop(config.xrayConfigJson, tunFd)
        }.onFailure { error ->
            AndroidAppLogger.error(LogTag, "Failed to start AndroidLibXrayLite", error)
            runCatching { controller.stopLoop() }
                .onFailure { stopError ->
                    AndroidAppLogger.warn(LogTag, "Failed to stop AndroidLibXrayLite after start failure", stopError)
                }
            throw IllegalStateException(
                context.getString(R.string.error_android_lib_xray_lite_start_failed, error.readableMessage()),
                error,
            )
        }
        coreController = controller
    }

    fun stop() {
        val controller = coreController ?: return
        runCatching {
            controller.stopLoop()
        }.onFailure { error ->
            AndroidAppLogger.error(LogTag, "Failed to stop AndroidLibXrayLite", error)
        }
        coreController = null
    }

    fun isRunning(): Boolean {
        return coreController?.isRunning == true
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
