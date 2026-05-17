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
import ui.KeyColors
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
internal fun settingsColorModeOptions() = listOf(
    stringResource(R.string.option_follow_system),
    stringResource(R.string.option_light),
    stringResource(R.string.option_dark),
    stringResource(R.string.option_theme_system),
    stringResource(R.string.option_theme_light),
    stringResource(R.string.option_theme_dark),
)

@Composable
internal fun settingsLanguageOptions() = listOf(
    stringResource(R.string.option_follow_system),
    stringResource(R.string.option_english),
    stringResource(R.string.option_simplified_chinese),
)

@Composable
internal fun settingsRunModeOptions() = listOf(
    stringResource(R.string.settings_run_mode_vpn_service),
    stringResource(R.string.settings_run_mode_tproxy),
)

@Composable
internal fun settingsKeyColorOptions() = listOf(
    stringResource(R.string.theme_color_default),
    stringResource(R.string.theme_color_blue),
    stringResource(R.string.theme_color_green),
    stringResource(R.string.theme_color_violet),
    stringResource(R.string.theme_color_yellow),
    stringResource(R.string.theme_color_orange),
    stringResource(R.string.theme_color_rose),
    stringResource(R.string.theme_color_cyan),
).take(KeyColors.size + 1)

@Composable
internal fun inboundProxySummary(
    transparentProxyPort: String,
    enableSocks5Proxy: Boolean,
    enableHttpProxy: Boolean,
): String {
    val enabledInbounds = mutableListOf(
        stringResource(R.string.settings_inbound_tproxy_port)
            .formatTemplate("port" to transparentProxyPort),
    )
    if (enableSocks5Proxy) {
        enabledInbounds += stringResource(R.string.settings_socks5_proxy)
    }
    if (enableHttpProxy) {
        enabledInbounds += stringResource(R.string.settings_http_proxy)
    }
    return stringResource(R.string.settings_inbound_selected)
        .formatTemplate("inbounds" to enabledInbounds.joinToString())
}

@Composable
internal fun localProxySettingsSummary(
    port: String,
    enableDynamicPort: Boolean,
    listenAllInterfaces: Boolean,
): String {
    if (enableDynamicPort) {
        return stringResource(R.string.settings_local_proxy_summary_dynamic)
    }
    val summary = if (listenAllInterfaces) {
        stringResource(R.string.settings_local_proxy_summary_all_interfaces)
    } else {
        stringResource(R.string.settings_local_proxy_summary_fixed)
    }
    return summary.formatTemplate("port" to port)
}
