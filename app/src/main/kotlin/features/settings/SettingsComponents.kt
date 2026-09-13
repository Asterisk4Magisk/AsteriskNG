// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

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
