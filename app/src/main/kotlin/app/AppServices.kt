// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.staticCompositionLocalOf
import android.net.Uri
import data.backup.AppBackupUseCase
import engine.proxy.AndroidProxyEngine
import engine.proxy.latency.AndroidProxyLatencyTester
import features.logs.CoreLogRepository
import features.proxy.server.usecase.ProxyServerImportFileUseCase
import features.proxy.server.usecase.ProxyServiceUseCase
import features.resources.ResourceFileUseCase
import features.settings.usecase.SwitchRunModeUseCase
import features.settings.usecase.RootBootScriptUseCase
import features.settings.usecase.RootEbpfProbeUseCase
import features.subscription.runtime.AndroidSubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import system.AndroidNetworkInterfaceProvider
import system.AndroidPackageProvider
import system.AndroidRootShellGateway
import system.AndroidUserSpaceProvider
import ui.feedback.AndroidToastTipNotifier

internal data class AppServices(
    val appScope: CoroutineScope,
    val proxyEngine: AndroidProxyEngine,
    val rootAccess: AndroidRootShellGateway,
    val userSpaces: AndroidUserSpaceProvider,
    val packageCatalog: AndroidPackageProvider,
    val networkInterfaces: AndroidNetworkInterfaceProvider,
    val resourceFileUseCase: ResourceFileUseCase,
    val appBackupUseCase: AppBackupUseCase,
    val subscriptionFetcher: AndroidSubscriptionFetcher,
    val qrScanner: suspend () -> String?,
    val proxyServerImportFileUseCase: ProxyServerImportFileUseCase,
    val proxyLatencyTester: AndroidProxyLatencyTester,
    val proxyServiceUseCase: ProxyServiceUseCase,
    val switchRunModeUseCase: SwitchRunModeUseCase,
    val rootBootScriptUseCase: RootBootScriptUseCase,
    val rootEbpfProbeUseCase: RootEbpfProbeUseCase,
    val tipNotifier: AndroidToastTipNotifier,
    val logFileCreator: suspend (String) -> Uri?,
    val coreLogRepository: CoreLogRepository,
    val accessLogRepository: CoreLogRepository,
    val logcatRepository: CoreLogRepository,
)

internal val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("LocalAppServices is not provided")
}
