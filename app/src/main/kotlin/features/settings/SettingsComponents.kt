// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import ui.text.formatTemplate

internal val SettingsLogLevelOptions = listOf("debug", "info", "warning", "error", "none")

@Composable
internal fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomPadding),
    ) {
        content()
    }
}

@Composable
internal fun inboundProxySummary(
    runMode: Int,
    transparentProxyPort: String,
    bpf2SocksBridgePort: String,
    socks5ProxyPort: String,
    enableHttpProxy: Boolean,
): String {
    val primaryInbound = when (runMode) {
        RunModeBpf2Socks -> stringResource(R.string.settings_inbound_bpf2socks_summary)
            .formatTemplate(
                "bridgePort" to bpf2SocksBridgePort,
                "socksPort" to socks5ProxyPort,
            )

        RunModeTun2Socks -> stringResource(R.string.settings_inbound_socks5_port)
            .formatTemplate("port" to socks5ProxyPort)

        else -> stringResource(R.string.settings_inbound_tproxy_port)
            .formatTemplate("port" to transparentProxyPort)
    }
    val enabledInbounds = mutableListOf<String>()
    if (enableHttpProxy) {
        enabledInbounds += stringResource(R.string.settings_http_proxy)
    }
    if (enabledInbounds.isEmpty()) {
        return primaryInbound
    }
    return listOf(
        primaryInbound,
        stringResource(R.string.settings_inbound_selected)
            .formatTemplate("inbounds" to enabledInbounds.joinToString()),
    ).joinToString()
}

@Composable
internal fun localProxySettingsSummary(
    port: String,
    listenAllInterfaces: Boolean,
): String {
    val summary = if (listenAllInterfaces) {
        stringResource(R.string.settings_local_proxy_summary_all_interfaces)
    } else {
        stringResource(R.string.settings_local_proxy_summary_fixed)
    }
    return summary.formatTemplate("port" to port)
}
