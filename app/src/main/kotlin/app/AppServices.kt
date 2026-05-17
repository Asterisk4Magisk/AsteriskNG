// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.staticCompositionLocalOf
import engine.proxy.AndroidProxyEngine
import engine.proxy.latency.AndroidProxyLatencyTester
import features.logs.CoreLogClearUseCase
import features.logs.CoreLogRepository
import features.proxy.server.qr.ProxyServerQrScanUseCase
import features.proxy.server.usecase.ProxyServiceUseCase
import features.resources.ResourceFileUseCase
import features.settings.usecase.SwitchRunModeUseCase
import features.settings.usecase.TproxyBootScriptUseCase
import features.subscription.SubscriptionFetchUseCase
import system.AndroidNetworkInterfaceProvider
import system.AndroidPackageProvider
import system.AndroidRootShellGateway
import system.AndroidUserSpaceProvider
import ui.feedback.AndroidToastTipNotifier

internal data class AppServices(
    val proxyEngine: AndroidProxyEngine,
    val rootAccess: AndroidRootShellGateway,
    val userSpaces: AndroidUserSpaceProvider,
    val packageCatalog: AndroidPackageProvider,
    val networkInterfaces: AndroidNetworkInterfaceProvider,
    val resourceFileUseCase: ResourceFileUseCase,
    val subscriptionFetchUseCase: SubscriptionFetchUseCase,
    val qrScanner: ProxyServerQrScanUseCase,
    val proxyLatencyTester: AndroidProxyLatencyTester,
    val proxyServiceUseCase: ProxyServiceUseCase,
    val switchRunModeUseCase: SwitchRunModeUseCase,
    val tproxyBootScriptUseCase: TproxyBootScriptUseCase,
    val tipNotifier: AndroidToastTipNotifier,
    val coreLogClearUseCase: CoreLogClearUseCase,
    val coreLogRepository: CoreLogRepository,
    val accessLogRepository: CoreLogRepository,
    val logcatRepository: CoreLogRepository,
)

internal val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("LocalAppServices is not provided")
}
