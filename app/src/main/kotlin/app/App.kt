// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.effects.ProxyStatusSynchronizer
import app.effects.SubscriptionAutoUpdater
import app.effects.TproxyBootScriptSynchronizer
import features.logs.AndroidAccessLogRepository
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidLogcatRepository
import features.logs.CoreLogClearUseCase
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.proxy.latency.AndroidProxyLatencyTester
import features.proxy.server.qr.ProxyServerQrScanUseCase
import features.proxy.server.usecase.ProxyServiceUseCase
import features.resources.ResourceFileUseCase
import features.settings.locale.ProvideAppLanguage
import features.settings.usecase.SwitchRunModeUseCase
import features.settings.usecase.TproxyBootScriptUseCase
import features.subscription.SubscriptionFetchUseCase
import system.AndroidNetworkInterfaceProvider
import system.AndroidPackageProvider
import system.AndroidRootShellGateway
import system.AndroidUserSpaceProvider
import ui.AppTheme
import ui.feedback.AndroidToastTipNotifier
import ui.keyColorFor

@Composable
fun App(
    padding: PaddingValues = PaddingValues(0.dp),
    qrCodeScanner: suspend () -> String?,
    resourceFilePicker: suspend () -> Uri?,
    requestVpnPermission: suspend (Intent) -> Boolean,
) {
    val appContext = LocalContext.current.applicationContext
    val rootAccess = remember { AndroidRootShellGateway() }
    val userSpaces = remember(appContext, rootAccess) {
        AndroidUserSpaceProvider(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val packageCatalog = remember(appContext, rootAccess, userSpaces) {
        AndroidPackageProvider(
            context = appContext,
            rootAccess = rootAccess,
            userSpaces = userSpaces,
        )
    }
    val networkInterfaces = remember(rootAccess) {
        AndroidNetworkInterfaceProvider(rootAccess)
    }
    val resourceFileUseCase = remember(appContext, resourceFilePicker) {
        ResourceFileUseCase(
            context = appContext,
            resourceFilePicker = resourceFilePicker,
        )
    }
    val subscriptionFetchUseCase = remember { SubscriptionFetchUseCase() }
    val qrScanner = remember(qrCodeScanner) {
        ProxyServerQrScanUseCase(qrCodeScanner)
    }
    val proxyLatencyTester = remember(appContext) {
        AndroidProxyLatencyTester(appContext)
    }
    val proxyEngine = remember(appContext, rootAccess) {
        AndroidProxyEngine(
            context = appContext,
            rootAccess = rootAccess,
            requestVpnPermission = requestVpnPermission,
        )
    }
    val tproxyBootScriptUseCase = remember(appContext, rootAccess) {
        TproxyBootScriptUseCase(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val switchRunModeUseCase = remember(proxyEngine, rootAccess, tproxyBootScriptUseCase) {
        SwitchRunModeUseCase(
            proxyEngine = proxyEngine,
            rootAccess = rootAccess,
            tproxyBootScriptUseCase = tproxyBootScriptUseCase,
        )
    }
    val proxyServiceUseCase = remember(proxyEngine) {
        ProxyServiceUseCase(proxyEngine)
    }
    val stateStore = remember(appContext) { AndroidAppStateStore.get(appContext) }
    val tipNotifier = remember(appContext) { AndroidToastTipNotifier(appContext) }
    val coreLogClearUseCase = remember(appContext, rootAccess) {
        CoreLogClearUseCase(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val services = remember(
        proxyEngine,
        rootAccess,
        userSpaces,
        packageCatalog,
        networkInterfaces,
        resourceFileUseCase,
        subscriptionFetchUseCase,
        qrScanner,
        proxyLatencyTester,
        proxyServiceUseCase,
        switchRunModeUseCase,
        tproxyBootScriptUseCase,
        tipNotifier,
        coreLogClearUseCase,
    ) {
        AppServices(
            proxyEngine = proxyEngine,
            rootAccess = rootAccess,
            userSpaces = userSpaces,
            packageCatalog = packageCatalog,
            networkInterfaces = networkInterfaces,
            resourceFileUseCase = resourceFileUseCase,
            subscriptionFetchUseCase = subscriptionFetchUseCase,
            qrScanner = qrScanner,
            proxyLatencyTester = proxyLatencyTester,
            proxyServiceUseCase = proxyServiceUseCase,
            switchRunModeUseCase = switchRunModeUseCase,
            tproxyBootScriptUseCase = tproxyBootScriptUseCase,
            tipNotifier = tipNotifier,
            coreLogClearUseCase = coreLogClearUseCase,
            coreLogRepository = AndroidCoreLogRepository,
            accessLogRepository = AndroidAccessLogRepository,
            logcatRepository = AndroidLogcatRepository,
        )
    }
    val chromeState by stateStore.collectAppChromeState()
    val updateAppState: ((AppState) -> AppState) -> Unit = remember(stateStore) {
        { transform -> stateStore.update(transform) }
    }
    val keyColor = keyColorFor(chromeState.seedIndex)
    ProxyStatusSynchronizer(
        stateStore = stateStore,
        proxyEngine = proxyEngine,
        updateAppState = updateAppState,
    )
    SubscriptionAutoUpdater(
        stateStore = stateStore,
        subscriptionFetchUseCase = subscriptionFetchUseCase,
        updateAppState = updateAppState,
    )
    TproxyBootScriptSynchronizer(
        stateStore = stateStore,
        tproxyBootScriptUseCase = tproxyBootScriptUseCase,
    )

    ProvideAppLanguage(languageMode = chromeState.languageMode) {
        AppTheme(
            colorMode = chromeState.colorMode,
            keyColor = keyColor,
        ) {
            CompositionLocalProvider(
                LocalAppStateStore provides stateStore,
                LocalAppChromeState provides chromeState,
                LocalUpdateAppState provides updateAppState,
                LocalAppServices provides services,
            ) {
                AppContent(padding = padding)
            }
        }
    }
}
