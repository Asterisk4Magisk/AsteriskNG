// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import engine.network.isIpAddress
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import ui.components.StringListEditor
import ui.components.sanitizeStringListItems


private const val DnsHostSeparator = ':'
private val XrayDnsUrlSchemes = setOf(
    "https",
    "h2c",
    "https+local",
    "h2c+local",
    "quic+local",
    "tcp",
    "tcp+local",
)


@Composable
internal fun DnsSettingsBottomSheet(
    show: Boolean,
    enableVpnLocalDns: Boolean,
    forceEnableLocalDns: Boolean,
    enableFakeDns: Boolean,
    enableResolveProxyServerDomain: Boolean,
    onEnableVpnLocalDnsChange: (Boolean) -> Unit,
    proxyDns: List<String>,
    directDns: List<String>,
    directDnsDomains: List<String>,
    enableDirectDnsForProxyServerDomains: Boolean,
    dnsHosts: List<String>,
    onEnableFakeDnsChange: (Boolean) -> Unit,
    onEnableResolveProxyServerDomainChange: (Boolean) -> Unit,
    onProxyDnsChange: (List<String>) -> Unit,
    onDirectDnsChange: (List<String>) -> Unit,
    onDirectDnsDomainsChange: (List<String>) -> Unit,
    onEnableDirectDnsForProxyServerDomainsChange: (Boolean) -> Unit,
    onDnsHostsChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (Boolean, Boolean, Boolean, List<String>, List<String>, List<String>, Boolean, List<String>) -> Unit,
) {
    val proxyDnsEntries = proxyDns.sanitizeStringListItems()
    val directDnsEntries = directDns.sanitizeStringListItems()
    val directDnsDomainEntries = directDnsDomains.sanitizeStringListItems()
    val dnsHostsInvalidMessage = stringResource(R.string.settings_dns_hosts_invalid)
    val dnsHostEntries = dnsHosts.sanitizeStringListItems()
    val dnsServerInvalidMessage = stringResource(R.string.settings_dns_server_invalid)
    val dnsDomainInvalidMessage = stringResource(R.string.settings_dns_domain_invalid)
    val effectiveLocalDnsEnabled = forceEnableLocalDns || enableVpnLocalDns
    val effectiveFakeDnsEnabled = effectiveLocalDnsEnabled && enableFakeDns
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_dns),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    onSave(
                        enableVpnLocalDns,
                        effectiveFakeDnsEnabled,
                        enableResolveProxyServerDomain,
                        proxyDnsEntries,
                        directDnsEntries,
                        directDnsDomainEntries,
                        enableDirectDnsForProxyServerDomains,
                        dnsHostEntries,
                    )
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        SettingsSheetContent {
            SwitchPreference(
                title = stringResource(R.string.settings_vpn_local_dns),
                summary = stringResource(R.string.settings_vpn_local_dns_summary),
                checked = effectiveLocalDnsEnabled,
                onCheckedChange = { enabled ->
                    if (!forceEnableLocalDns) {
                        onEnableVpnLocalDnsChange(enabled)
                    }
                },
            )
            AnimatedVisibility(
                visible = effectiveLocalDnsEnabled,
                enter = fadeIn() + expandVertically(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SwitchPreference(
                    title = "FakeDNS",
                    summary = stringResource(R.string.settings_fake_dns_summary),
                    checked = effectiveFakeDnsEnabled,
                    onCheckedChange = onEnableFakeDnsChange,
                )
            }
            SwitchPreference(
                title = stringResource(R.string.settings_resolve_proxy_server_domain),
                summary = stringResource(R.string.settings_resolve_proxy_server_domain_summary),
                checked = enableResolveProxyServerDomain,
                onCheckedChange = onEnableResolveProxyServerDomainChange,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_direct_dns_resolve_proxy_server_domains),
                summary = stringResource(R.string.settings_direct_dns_resolve_proxy_server_domains_summary),
                checked = enableDirectDnsForProxyServerDomains,
                onCheckedChange = onEnableDirectDnsForProxyServerDomainsChange,
            )
            Spacer(Modifier.height(8.dp))
            StringListEditor(
                editorKey = "direct-dns:$show",
                title = stringResource(R.string.settings_direct_dns),
                inputLabel = stringResource(R.string.settings_direct_dns_input),
                values = directDnsEntries,
                onValuesChange = { onDirectDnsChange(it.sanitizeStringListItems()) },
                emptyText = stringResource(R.string.settings_direct_dns_empty),
                duplicateText = stringResource(R.string.settings_dns_hosts_duplicate),
                validateInput = { dnsServerInputError(it, dnsServerInvalidMessage) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            StringListEditor(
                editorKey = "direct-dns-domains:$show",
                title = stringResource(R.string.settings_direct_dns_domains),
                inputLabel = stringResource(R.string.settings_direct_dns_domains_input),
                values = directDnsDomainEntries,
                onValuesChange = { onDirectDnsDomainsChange(it.sanitizeStringListItems()) },
                emptyText = stringResource(R.string.settings_direct_dns_domains_empty),
                duplicateText = stringResource(R.string.settings_dns_hosts_duplicate),
                validateInput = { dnsDomainInputError(it, dnsDomainInvalidMessage) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            StringListEditor(
                editorKey = "proxy-dns:$show",
                title = stringResource(R.string.settings_proxy_dns),
                inputLabel = stringResource(R.string.settings_proxy_dns_input),
                values = proxyDnsEntries,
                onValuesChange = { onProxyDnsChange(it.sanitizeStringListItems()) },
                emptyText = stringResource(R.string.settings_proxy_dns_empty),
                duplicateText = stringResource(R.string.settings_dns_hosts_duplicate),
                validateInput = { dnsServerInputError(it, dnsServerInvalidMessage) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            StringListEditor(
                editorKey = "dns-hosts:$show",
                title = stringResource(R.string.settings_dns_hosts),
                description = stringResource(R.string.settings_dns_hosts_format),
                inputLabel = stringResource(R.string.settings_dns_hosts_input),
                values = dnsHostEntries,
                onValuesChange = { onDnsHostsChange(it.sanitizeStringListItems()) },
                emptyText = stringResource(R.string.settings_dns_hosts_empty),
                duplicateText = stringResource(R.string.settings_dns_hosts_duplicate),
                validateInput = { dnsHostInputError(it, dnsHostsInvalidMessage) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun dnsServerInputError(input: String, invalidMessage: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return invalidMessage
    return if (isXrayDnsServer(trimmed)) null else invalidMessage
}

private fun isXrayDnsServer(value: String): Boolean {
    if (value.equals("localhost", ignoreCase = true) || value.equals("fakedns", ignoreCase = true)) {
        return true
    }

    val schemeEnd = value.indexOf("://")
    if (schemeEnd >= 0) {
        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme !in XrayDnsUrlSchemes) return false
        val authority = value.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        return isXrayDnsAuthority(authority)
    }

    return isIpAddress(value) || (!value.contains(":") && isDnsHostDomain(value))
}

private fun isXrayDnsAuthority(authority: String): Boolean {
    val trimmed = authority.trim()
    if (trimmed.isBlank()) return false

    if (trimmed.startsWith("[")) {
        val closeBracketIndex = trimmed.indexOf(']')
        if (closeBracketIndex <= 1) return false
        val host = trimmed.substring(1, closeBracketIndex)
        val rest = trimmed.substring(closeBracketIndex + 1)
        return isIpAddress(host) && (rest.isEmpty() || rest.startsWith(":") && isPort(rest.drop(1)))
    }

    val colonCount = trimmed.count { it == ':' }
    if (colonCount == 0) {
        return isIpAddress(trimmed) || isDnsHostDomain(trimmed)
    }
    if (colonCount == 1) {
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':')
        return (isIpAddress(host) || isDnsHostDomain(host)) && isPort(port)
    }

    return isIpAddress(trimmed)
}

private fun dnsDomainInputError(input: String, invalidMessage: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return invalidMessage
    if (trimmed.startsWith("regexp:", ignoreCase = true)) {
        return if (trimmed.substringAfter(":").isBlank()) invalidMessage else null
    }

    val supportedPrefix = trimmed.substringBefore(":", missingDelimiterValue = "")
        .lowercase()
        .takeIf { it in setOf("domain", "full", "keyword", "geosite", "ext") }
    if (supportedPrefix != null) {
        return if (trimmed.substringAfter(":").isBlank()) invalidMessage else null
    }

    return if (trimmed.contains("://") || trimmed.contains("/")) invalidMessage else null
}

private fun dnsHostInputError(input: String, invalidMessage: String): String? {
    val separatorIndex = input.indexOf(DnsHostSeparator)
    if (separatorIndex <= 0 || separatorIndex == input.lastIndex) return invalidMessage

    val domain = input.substring(0, separatorIndex).trim()
    val addresses = input.substring(separatorIndex + 1)
        .split(",")
        .map { it.trim().trim('[', ']') }

    if (!isDnsHostDomain(domain)) return invalidMessage
    if (addresses.isEmpty() || addresses.any { it.isEmpty() || !isIpAddress(it) }) return invalidMessage
    return null
}

private fun isDnsHostDomain(domain: String): Boolean {
    val normalized = domain.removeSuffix(".")
    if (normalized.isEmpty() || normalized.length > 253) return false
    if (normalized.any { it.isWhitespace() || it == '/' || it == DnsHostSeparator }) return false

    return normalized.split(".").all { label ->
        label.isNotEmpty() &&
            label.length <= 63 &&
            label.first() != '-' &&
            label.last() != '-' &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}
