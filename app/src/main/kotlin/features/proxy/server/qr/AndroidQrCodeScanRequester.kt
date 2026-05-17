package features.proxy.server.qr

import android.Manifest
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidQrCodeScanRequester(
    private val hasCameraPermission: () -> Boolean,
    private val permissionDeniedMessage: () -> String,
    private val missingLauncherMessage: () -> String,
) {
    private val mutex = Mutex()
    private var permissionLauncher: ((String) -> Unit)? = null
    private var scanLauncher: ((ScanOptions) -> Unit)? = null
    private var pendingPermissionResult: CompletableDeferred<Boolean>? = null
    private var pendingScanResult: CompletableDeferred<String?>? = null

    fun registerPermissionLauncher(launcher: ((String) -> Unit)?) {
        permissionLauncher = launcher
    }

    fun registerScanLauncher(launcher: ((ScanOptions) -> Unit)?) {
        scanLauncher = launcher
    }

    fun completeCameraPermission(granted: Boolean) {
        pendingPermissionResult?.complete(granted)
        pendingPermissionResult = null
    }

    fun completeScan(text: String?) {
        pendingScanResult?.complete(text)
        pendingScanResult = null
    }

    suspend fun scan(): String? {
        return mutex.withLock {
            if (!ensureCameraPermission()) {
                error(permissionDeniedMessage())
            }
            val launcher = scanLauncher ?: error(missingLauncherMessage())
            val result = CompletableDeferred<String?>()
            pendingScanResult = result
            try {
                withContext(Dispatchers.Main.immediate) {
                    launcher(
                        ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setCaptureActivity(PortraitQrCaptureActivity::class.java)
                            setOrientationLocked(true)
                            setBeepEnabled(false)
                            setPrompt("")
                        },
                    )
                }
                result.await()
            } finally {
                if (pendingScanResult === result) {
                    pendingScanResult = null
                }
            }
        }
    }

    private suspend fun ensureCameraPermission(): Boolean {
        if (hasCameraPermission()) {
            return true
        }
        val launcher = permissionLauncher ?: error(missingLauncherMessage())
        val result = CompletableDeferred<Boolean>()
        pendingPermissionResult = result
        return try {
            withContext(Dispatchers.Main.immediate) {
                launcher(Manifest.permission.CAMERA)
            }
            result.await()
        } finally {
            if (pendingPermissionResult === result) {
                pendingPermissionResult = null
            }
        }
    }
}
