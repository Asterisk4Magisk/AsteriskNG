package app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import engine.vpn.AndroidVpnPermissionRequester
import features.resources.runtime.AndroidResourceFilePicker
import features.proxy.server.qr.AndroidQrCodeScanRequester
import com.journeyapps.barcodescanner.ScanContract

class MainActivity : ComponentActivity() {
    private val vpnPermissionRequester = AndroidVpnPermissionRequester {
        getString(R.string.error_vpn_permission_launcher_missing)
    }

    private val qrCodeScanRequester = AndroidQrCodeScanRequester(
        hasCameraPermission = {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        permissionDeniedMessage = {
            getString(R.string.error_qr_camera_permission_denied)
        },
        missingLauncherMessage = {
            getString(R.string.error_qr_scan_launcher_missing)
        },
    )

    private val resourceFilePicker = AndroidResourceFilePicker(
        missingLauncherMessage = {
            getString(R.string.error_resource_file_picker_missing)
        },
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnPermissionRequester.complete(result.resultCode == RESULT_OK)
    }

    private val qrCodePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        qrCodeScanRequester.completeCameraPermission(granted)
    }

    private val qrCodeScanLauncher = registerForActivityResult(ScanContract()) { result ->
        qrCodeScanRequester.completeScan(result.contents)
    }

    private val resourceFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        resourceFilePicker.complete(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnPermissionRequester.registerLauncher { intent ->
            vpnPermissionLauncher.launch(intent)
        }
        qrCodeScanRequester.registerPermissionLauncher { permission ->
            qrCodePermissionLauncher.launch(permission)
        }
        qrCodeScanRequester.registerScanLauncher { options ->
            qrCodeScanLauncher.launch(options)
        }
        resourceFilePicker.registerLauncher { mimeTypes ->
            resourceFilePickerLauncher.launch(mimeTypes)
        }
        showAppContent()
        requestStartupPermissions()
    }

    override fun onDestroy() {
        vpnPermissionRequester.complete(false)
        vpnPermissionRequester.registerLauncher(null)
        qrCodeScanRequester.completeCameraPermission(false)
        qrCodeScanRequester.completeScan(null)
        qrCodeScanRequester.registerPermissionLauncher(null)
        qrCodeScanRequester.registerScanLauncher(null)
        resourceFilePicker.complete(null)
        resourceFilePicker.registerLauncher(null)
        super.onDestroy()
    }

    private fun requestStartupPermissions() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showAppContent() {
        enableEdgeToEdge()
        setContent {
            App(
                padding = WindowInsets.safeDrawing.asPaddingValues(),
                qrCodeScanner = qrCodeScanRequester::scan,
                resourceFilePicker = resourceFilePicker::pick,
                requestVpnPermission = vpnPermissionRequester::request,
            )
        }
    }
}
