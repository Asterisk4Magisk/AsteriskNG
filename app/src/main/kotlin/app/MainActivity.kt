// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import com.journeyapps.barcodescanner.ScanContract
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import engine.vpn.AndroidVpnPermissionRequester
import features.logs.AndroidLogFileCreator
import features.proxy.server.qr.AndroidQrCodeScanRequester
import features.resources.runtime.AndroidResourceFilePicker
import features.settings.locale.localizedAppContext
import features.subscription.SubscriptionInstallConfigUseCase
import features.subscription.isSubscriptionInstallConfigUri
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.subscriptionInstallMessage
import features.subscription.toSubscriptionInstallConfigOrNull
import kotlinx.coroutines.launch
import ui.feedback.AndroidToastTipNotifier

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

    private val logFileCreator = AndroidLogFileCreator(
        missingLauncherMessage = {
            getString(R.string.error_log_export_launcher_missing)
        },
    )
    private val tipNotifier by lazy { AndroidToastTipNotifier(this) }

    private val subscriptionInstallConfigUseCase by lazy {
        SubscriptionInstallConfigUseCase(
            stateStore = AndroidAppStateStore.get(this),
            subscriptionFetcher = AndroidSubscriptionFetcher(),
        )
    }

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

    private val logFileCreatorLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        logFileCreator.complete(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        val settings = AppSettingsPreferences(newBase).load()
        super.attachBaseContext(
            newBase.localizedAppContext(settings.languageMode, settings.colorMode),
        )
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
        logFileCreator.registerLauncher { fileName ->
            logFileCreatorLauncher.launch(fileName)
        }
        showAppContent()
        requestStartupPermissions()
        if (savedInstanceState == null) {
            handleExternalIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
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
        logFileCreator.complete(null)
        logFileCreator.registerLauncher(null)
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
                padding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues(),
                qrCodeScanner = qrCodeScanRequester::scan,
                resourceFilePicker = resourceFilePicker::pick,
                logFileCreator = logFileCreator::create,
                requestVpnPermission = vpnPermissionRequester::request,
            )
        }
    }

    private fun handleExternalIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.isSubscriptionInstallConfigUri()) return
        val config = intent.toSubscriptionInstallConfigOrNull()
        if (config == null) {
            (application as AsteriskApplication).appScope.launch {
                tipNotifier.show(getString(R.string.subscription_install_config_invalid))
            }
            return
        }
        (application as AsteriskApplication).appScope.launch {
            runCatching {
                subscriptionInstallConfigUseCase.install(config)
            }.onSuccess { result ->
                tipNotifier.show(
                    subscriptionInstallMessage(
                        result = result,
                        existingUrlTemplate = getString(R.string.subscription_install_existing_url),
                        successTemplate = getString(R.string.proxy_server_list_subscription_update_result),
                        failedTemplate = getString(R.string.proxy_server_list_subscription_update_result_with_failed),
                    ),
                )
            }.onFailure { error ->
                tipNotifier.showError(error, getString(R.string.subscription_install_config_failed))
            }
        }
    }
}
