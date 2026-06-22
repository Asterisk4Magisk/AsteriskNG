// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tun2socks

import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.root.RootXrayGid
import engine.root.RootIptablesConfig
import engine.root.RootProxyRouteRulePriority
import engine.root.RootProxyAppWhitelistSystemUids
import engine.root.appendDeleteRuleLoop
import engine.root.appendIpRuleDeleteLoop
import engine.root.appendRootEbpfXtbpfInterfaceMarkRules
import engine.root.appendRootEbpfXtbpfMarkRules
import engine.root.appendRootFakeDnsIcmpReplyCleanupRules
import engine.root.appendRootFakeDnsIcmpReplyRules
import engine.root.appendRootIpv6DnsRejectCleanupRules
import engine.root.appendRootIpv6DnsRejectRules
import engine.root.appendScript
import utils.shellQuote

internal fun RootIptablesConfig.buildSetupRulesCommand(
    enableIpv6: Boolean,
    enableLocalDns: Boolean,
    enableFakeDns: Boolean,
): String {
    return buildString {
        append(buildCleanupRulesCommand())
        appendIptablesVariantSetupRules(
            config = this@buildSetupRulesCommand,
            variant = Tun2SocksIptablesVariant.forIpv4(this@buildSetupRulesCommand),
            enableLocalDns = enableLocalDns,
        )
        if (enableIpv6) {
            appendIptablesVariantSetupRules(
                config = this@buildSetupRulesCommand,
                variant = Tun2SocksIptablesVariant.forIpv6(this@buildSetupRulesCommand),
                enableLocalDns = enableLocalDns,
            )
        }
        if (enableLocalDns) {
            appendRootIpv6DnsRejectRules()
        }
        if (enableFakeDns) {
            appendRootFakeDnsIcmpReplyRules()
        }
    }
}

internal fun RootIptablesConfig.buildCleanupRulesCommand(): String {
    return buildString {
        appendIptablesVariantCleanupRules(this@buildCleanupRulesCommand, Tun2SocksIptablesVariant.forIpv4(this@buildCleanupRulesCommand))
        appendRootFakeDnsIcmpReplyCleanupRules()
        appendRootIpv6DnsRejectCleanupRules()
        appendIptablesVariantCleanupRules(this@buildCleanupRulesCommand, Tun2SocksIptablesVariant.forIpv6(this@buildCleanupRulesCommand))
    }
}

private fun StringBuilder.appendIptablesVariantSetupRules(
    config: RootIptablesConfig,
    variant: Tun2SocksIptablesVariant,
    enableLocalDns: Boolean,
) {
    appendScript(
        """
        ${variant.ipCommand} rule add priority $RootProxyRouteRulePriority fwmark ${config.mark} lookup ${variant.routeTable} 2>/dev/null || true
        ${variant.ipCommand} route add default dev 'asterisk0' table ${variant.routeTable} 2>/dev/null || true
        ${variant.command} -t mangle -N ${variant.preroutingChain} 2>/dev/null || true
        ${variant.command} -t mangle -N ${variant.outputChain} 2>/dev/null || true
        ${variant.command} -t filter -N ${variant.forwardChain} 2>/dev/null || true
        ${variant.command} -t mangle -I PREROUTING 1 -j ${variant.preroutingChain}
        ${variant.command} -t mangle -I OUTPUT 1 -j ${variant.outputChain}
        ${variant.command} -t filter -I FORWARD 1 -j ${variant.forwardChain}
        ${variant.command} -t filter -A ${variant.forwardChain} -i 'asterisk0' -j ACCEPT
        ${variant.command} -t filter -A ${variant.forwardChain} -o 'asterisk0' -j ACCEPT
        """,
    )
    if (config.enableEbpfRules) {
        if (enableLocalDns) {
            appendUdpDnsMarkRule(variant.command, variant.preroutingChain, config.mark)
        }
        appendDestinationMarkRules(
            command = variant.command,
            chain = variant.preroutingChain,
            cidrs = variant.proxyPrivateCidrs,
            mark = config.mark,
        )
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.preroutingChain,
            cidrs = variant.bypassPrivateCidrs + variant.localInterfaceCidrs,
            interfaces = emptyList(),
            input = true,
        )
        appendRootEbpfXtbpfInterfaceMarkRules(
            command = variant.command,
            chain = variant.preroutingChain,
            routeMark = config.mark,
            interfacePrefixes = config.externalInterfacePrefixes,
        )
        appendOutputUidReturnRules(variant.command, variant.outputChain, config.forcedBypassUids)
        if (enableLocalDns) {
            appendUdpDnsMarkRule(
                command = variant.command,
                chain = variant.outputChain,
                mark = config.mark,
                ownerBypassGid = RootXrayGid,
            )
        }
        appendDestinationMarkRules(
            command = variant.command,
            chain = variant.outputChain,
            cidrs = variant.proxyPrivateCidrs,
            mark = config.mark,
        )
        appendScript("${variant.command} -t mangle -A ${variant.outputChain} -o 'asterisk0' -j RETURN")
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.outputChain,
            cidrs = emptyList(),
            interfaces = config.ignoredInterfaces,
            input = false,
        )
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.outputChain,
            cidrs = variant.bypassPrivateCidrs + variant.localInterfaceCidrs,
            interfaces = emptyList(),
            input = false,
        )
        appendRootEbpfXtbpfMarkRules(
            command = variant.command,
            chain = variant.outputChain,
            routeMark = config.mark,
            ownerBypassGid = RootXrayGid,
        )
        return
    }
    appendPreroutingTrafficMarkRules(config, variant, enableLocalDns)
    appendOutputTrafficMarkRules(config, variant, enableLocalDns)
}

private fun StringBuilder.appendIptablesVariantCleanupRules(
    config: RootIptablesConfig,
    variant: Tun2SocksIptablesVariant,
) {
    appendDeleteRuleLoop(variant.command, "PREROUTING", "-j ${variant.preroutingChain}")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-j ${variant.outputChain}")
    appendDeleteRuleLoop(variant.command, "FORWARD", "-j ${variant.forwardChain}", table = "filter")
    listOf(variant.preroutingChain, variant.outputChain).forEach { chain ->
        appendScript(
            """
            ${variant.command} -t mangle -F $chain 2>/dev/null || true
            ${variant.command} -t mangle -X $chain 2>/dev/null || true
            """,
        )
    }
    appendScript(
        """
        ${variant.command} -t filter -F ${variant.forwardChain} 2>/dev/null || true
        ${variant.command} -t filter -X ${variant.forwardChain} 2>/dev/null || true
        """,
    )
    appendIpRuleDeleteLoop(
        ipCommand = variant.ipCommand,
        rule = "priority $RootProxyRouteRulePriority fwmark ${config.mark} lookup ${variant.routeTable}",
    )
    appendScript("${variant.ipCommand} route flush table ${variant.routeTable} 2>/dev/null || true")
}

private fun StringBuilder.appendPreroutingTrafficMarkRules(
    config: RootIptablesConfig,
    variant: Tun2SocksIptablesVariant,
    enableLocalDns: Boolean,
) {
    if (enableLocalDns) {
        appendUdpDnsMarkRule(variant.command, variant.preroutingChain, config.mark)
    }
    appendDestinationMarkRules(
        command = variant.command,
        chain = variant.preroutingChain,
        cidrs = variant.proxyPrivateCidrs,
        mark = config.mark,
    )
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.preroutingChain,
        cidrs = variant.bypassPrivateCidrs + variant.localInterfaceCidrs,
        interfaces = emptyList(),
        input = true,
    )
    config.externalInterfacePrefixes.forEach { prefix ->
        appendPreroutingInterfaceMarkRules(variant.command, variant.preroutingChain, prefix, config.mark)
    }
}

private fun StringBuilder.appendOutputTrafficMarkRules(
    config: RootIptablesConfig,
    variant: Tun2SocksIptablesVariant,
    enableLocalDns: Boolean,
) {
    appendOutputUidReturnRules(variant.command, variant.outputChain, config.forcedBypassUids)
    if (enableLocalDns) {
        appendUdpDnsMarkRule(
            command = variant.command,
            chain = variant.outputChain,
            mark = config.mark,
            ownerBypassGid = RootXrayGid,
        )
    }
    appendDestinationMarkRules(
        command = variant.command,
        chain = variant.outputChain,
        cidrs = variant.proxyPrivateCidrs,
        mark = config.mark,
    )
    appendScript("${variant.command} -t mangle -A ${variant.outputChain} -o 'asterisk0' -j RETURN")
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.outputChain,
        cidrs = emptyList(),
        interfaces = config.ignoredInterfaces,
        input = false,
    )
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.outputChain,
        cidrs = variant.bypassPrivateCidrs + variant.localInterfaceCidrs,
        interfaces = emptyList(),
        input = false,
    )
    appendScript("${variant.command} -t mangle -A ${variant.outputChain} -m owner --gid-owner $RootXrayGid -j RETURN")
    appendOutputApplicationBypassRules(
        command = variant.command,
        chain = variant.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
    )
    appendOutputApplicationMarkRules(
        command = variant.command,
        chain = variant.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
        mark = config.mark,
    )
}

private fun StringBuilder.appendUdpDnsMarkRule(
    command: String,
    chain: String,
    mark: String,
    ownerBypassGid: Int? = null,
) {
    val ownerMatch = ownerBypassGid?.let { gid -> "-m owner ! --gid-owner $gid " }.orEmpty()
    appendScript("$command -t mangle -A $chain -p udp ${ownerMatch}-m udp --dport 53 -j MARK --set-xmark $mark")
}

private fun StringBuilder.appendDestinationMarkRules(
    command: String,
    chain: String,
    cidrs: List<String>,
    mark: String,
) {
    cidrs.asReversed().forEach { cidr ->
        val quotedCidr = cidr.shellQuote()
        appendScript(
            """
            $command -t mangle -A $chain -d $quotedCidr -p tcp -j MARK --set-xmark $mark
            $command -t mangle -A $chain -d $quotedCidr -p udp -j MARK --set-xmark $mark
            """,
        )
    }
}

private fun StringBuilder.appendBypassReturnRules(
    command: String,
    chain: String,
    cidrs: List<String>,
    interfaces: List<String>,
    input: Boolean,
) {
    cidrs.forEach { cidr ->
        appendScript("$command -t mangle -A $chain -d ${cidr.shellQuote()} -j RETURN")
    }
    interfaces.forEach { name ->
        val flag = if (input) "-i" else "-o"
        appendScript("$command -t mangle -A $chain $flag ${name.shellQuote()} -j RETURN")
    }
}

private fun StringBuilder.appendOutputApplicationBypassRules(
    command: String,
    chain: String,
    mode: Int,
    uids: List<Int>,
) {
    if (mode == ProxyAppListModeBlacklist) {
        appendOutputUidReturnRules(command, chain, uids)
    }
}

private fun StringBuilder.appendOutputApplicationMarkRules(
    command: String,
    chain: String,
    mode: Int,
    uids: List<Int>,
    mark: String,
) {
    when (mode) {
        ProxyAppListModeBlacklist,
        ProxyAppListModeGlobal -> appendOutputAllTrafficMarkRules(command, chain, mark)

        ProxyAppListModeWhitelist -> appendOutputUidMarkRules(command, chain, uids + RootProxyAppWhitelistSystemUids, mark)

        else -> appendOutputAllTrafficMarkRules(command, chain, mark)
    }
}

private fun StringBuilder.appendOutputAllTrafficMarkRules(
    command: String,
    chain: String,
    mark: String,
) {
    appendScript(
        """
        $command -t mangle -A $chain -p tcp -j MARK --set-xmark $mark
        $command -t mangle -A $chain -p udp -j MARK --set-xmark $mark
        """,
    )
}

private fun StringBuilder.appendOutputUidReturnRules(
    command: String,
    chain: String,
    uids: List<Int>,
) {
    uids.distinct().asReversed().forEach { uid ->
        appendScript("$command -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
    }
}

private fun StringBuilder.appendOutputUidMarkRules(
    command: String,
    chain: String,
    uids: List<Int>,
    mark: String,
) {
    uids.distinct().forEach { uid ->
        appendScript(
            """
            $command -t mangle -A $chain -p tcp -m owner --uid-owner $uid -j MARK --set-xmark $mark
            $command -t mangle -A $chain -p udp -m owner --uid-owner $uid -j MARK --set-xmark $mark
            """,
        )
    }
}

private fun StringBuilder.appendPreroutingInterfaceMarkRules(
    command: String,
    chain: String,
    interfaceName: String,
    mark: String,
) {
    val quotedInterface = interfaceName.shellQuote()
    appendScript(
        """
        $command -t mangle -A $chain -i $quotedInterface -p tcp -j MARK --set-xmark $mark
        $command -t mangle -A $chain -i $quotedInterface -p udp -j MARK --set-xmark $mark
        """,
    )
}
