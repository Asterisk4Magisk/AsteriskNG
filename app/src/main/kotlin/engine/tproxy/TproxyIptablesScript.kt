// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.root.RootXrayGid
import engine.root.RootIptablesConfig
import engine.root.RootProxyRouteRulePriority
import engine.root.RootProxyAppWhitelistSystemUids
import engine.root.appendDeleteRuleLoop
import engine.root.appendIpRuleDeleteLoop
import engine.root.appendRootEbpfXtbpfInterfaceTproxyRules
import engine.root.appendRootEbpfXtbpfMarkRules
import engine.root.appendRootFakeDnsIcmpReplyCleanupRules
import engine.root.appendRootFakeDnsIcmpReplyRules
import engine.root.appendRootIpv6DnsRejectCleanupRules
import engine.root.appendRootIpv6DnsRejectRules
import engine.root.appendScript
import utils.shellQuote

internal fun RootIptablesConfig.buildSetupRulesCommand(
    port: Int,
    enableIpv6: Boolean,
    enableLocalDns: Boolean,
    enableFakeDns: Boolean,
): String {
    return buildString {
        append(buildCleanupRulesCommand())
        appendIptablesVariantSetupRules(
            config = this@buildSetupRulesCommand,
            variant = ipv4IptablesVariant(),
            port = port,
            enableLocalDns = enableLocalDns,
        )
        if (enableIpv6) {
            appendIpv6VariantSetupRules(this@buildSetupRulesCommand, port, enableLocalDns)
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
        appendIptablesVariantCleanupRules(this@buildCleanupRulesCommand, ipv4IptablesVariant())
        appendRootFakeDnsIcmpReplyCleanupRules()
        appendRootIpv6DnsRejectCleanupRules()
        appendIptablesVariantCleanupRules(this@buildCleanupRulesCommand, ipv6IptablesVariant(useDummyInterface = false))
        appendIptablesVariantCleanupRules(this@buildCleanupRulesCommand, ipv6IptablesVariant(useDummyInterface = true))
    }
}

private fun StringBuilder.appendIpv6VariantSetupRules(
    config: RootIptablesConfig,
    port: Int,
    enableLocalDns: Boolean,
) {
    appendScript("if ${buildGlobalIpv6AddressCheckCommand()}; then")
    appendIptablesVariantSetupRules(config, config.ipv6IptablesVariant(useDummyInterface = false), port, enableLocalDns)
    appendScript("else")
    appendIptablesVariantSetupRules(config, config.ipv6IptablesVariant(useDummyInterface = true), port, enableLocalDns)
    appendScript("fi")
}

private fun StringBuilder.appendIptablesVariantSetupRules(
    config: RootIptablesConfig,
    variant: TproxyIptablesVariant,
    port: Int,
    enableLocalDns: Boolean,
) {
    if (variant.dummyInterface == null) {
        appendScript(
            """
            ${variant.ipCommand} rule add priority $RootProxyRouteRulePriority fwmark ${config.mark} table ${variant.routeTable} 2>/dev/null || true
            ${variant.ipCommand} route add local ${variant.routeDestination} dev lo table ${variant.routeTable} 2>/dev/null || true
            """,
        )
    } else {
        appendDummyRouteRules(variant)
    }
    appendScript(
        """
        ${variant.command} -t mangle -N ${variant.preroutingChain} 2>/dev/null || true
        ${variant.command} -t mangle -N ${variant.outputChain} 2>/dev/null || true
        ${variant.command} -t mangle -I PREROUTING 1 -j ${variant.preroutingChain}
        ${variant.command} -t mangle -I OUTPUT 1 -j ${variant.outputChain}
        """,
    )
    if (config.enableEbpfRules) {
        if (enableLocalDns) {
            appendPreroutingDnsTproxyRules(variant, port, config.mark)
        }
        appendPreroutingPrivateDestinationInterfaceTproxyRules(
            variant = variant,
            interfacePrefixes = config.externalInterfacePrefixes,
            port = port,
            mark = config.mark,
        )
        appendPreroutingPrivateDestinationMarkedTproxyRules(variant, port, config.mark)
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.preroutingChain,
            cidrs = variant.bypassPrivateCidrs,
            interfaces = emptyList(),
            input = true,
        )
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.preroutingChain,
            cidrs = variant.localInterfaceCidrs,
            interfaces = emptyList(),
            input = true,
        )
        appendPreroutingMarkedTproxyRules(variant, port, config.mark)
        appendEbpfPreroutingRules(config, variant, port)
        appendOutputUidReturnRules(variant.command, variant.outputChain, config.forcedBypassUids)
        if (enableLocalDns) {
            appendUdpDnsMarkRule(variant.command, variant.outputChain, config.mark, ownerBypassGid = RootXrayGid)
        }
        appendDestinationMarkRules(variant.command, variant.outputChain, variant.proxyPrivateCidrs, config.mark)
        variant.dummyInterface?.let { dummyInterface ->
            appendScript("${variant.command} -t mangle -A ${variant.outputChain} -o ${dummyInterface.device.shellQuote()} -j RETURN")
        }
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
            cidrs = variant.bypassPrivateCidrs,
            interfaces = emptyList(),
            input = false,
        )
        appendBypassReturnRules(
            command = variant.command,
            chain = variant.outputChain,
            cidrs = variant.localInterfaceCidrs,
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
    if (enableLocalDns) {
        appendPreroutingDnsTproxyRules(variant, port, config.mark)
    }
    appendPreroutingPrivateDestinationInterfaceTproxyRules(
        variant = variant,
        interfacePrefixes = config.externalInterfacePrefixes,
        port = port,
        mark = config.mark,
    )
    appendPreroutingPrivateDestinationMarkedTproxyRules(variant, port, config.mark)
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.preroutingChain,
        cidrs = variant.bypassPrivateCidrs,
        interfaces = emptyList(),
        input = true,
    )
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.preroutingChain,
        cidrs = variant.localInterfaceCidrs,
        interfaces = emptyList(),
        input = true,
    )
    appendPreroutingMarkedTproxyRules(variant, port, config.mark)
    config.externalInterfacePrefixes.forEach { prefix ->
        appendPreroutingInterfaceTproxyRules(variant, prefix, port, config.mark)
    }
    variant.dummyInterface?.let { dummyInterface ->
        appendDummyPreroutingRules(variant.command, dummyInterface, port)
    }
    appendOutputUidReturnRules(variant.command, variant.outputChain, config.forcedBypassUids)
    if (enableLocalDns) {
        appendUdpDnsMarkRule(variant.command, variant.outputChain, config.mark, ownerBypassGid = RootXrayGid)
    }
    appendDestinationMarkRules(variant.command, variant.outputChain, variant.proxyPrivateCidrs, config.mark)
    appendOutputApplicationBypassRules(
        command = variant.command,
        chain = variant.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
    )
    variant.dummyInterface?.let { dummyInterface ->
        appendScript("${variant.command} -t mangle -A ${variant.outputChain} -o ${dummyInterface.device.shellQuote()} -j RETURN")
    }
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
        cidrs = variant.bypassPrivateCidrs,
        interfaces = emptyList(),
        input = false,
    )
    appendBypassReturnRules(
        command = variant.command,
        chain = variant.outputChain,
        cidrs = variant.localInterfaceCidrs,
        interfaces = emptyList(),
        input = false,
    )
    appendScript("${variant.command} -t mangle -A ${variant.outputChain} -m owner --gid-owner $RootXrayGid -j RETURN")
    appendOutputApplicationMarkRules(
        command = variant.command,
        chain = variant.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
        mark = config.mark,
    )
    variant.dummyInterface?.let { dummyInterface ->
        appendDummyOutputRules(variant.command, dummyInterface)
    }
}

private fun StringBuilder.appendEbpfPreroutingRules(
    config: RootIptablesConfig,
    variant: TproxyIptablesVariant,
    port: Int,
) {
    appendRootEbpfXtbpfInterfaceTproxyRules(
        command = variant.command,
        chain = variant.preroutingChain,
        interfacePrefixes = config.externalInterfacePrefixes,
        port = port,
        onIp = variant.tproxyOnIp,
        mark = config.mark,
    )
    variant.dummyInterface?.let { dummyInterface ->
        appendDummyPreroutingRules(variant.command, dummyInterface, port)
    }
}

private fun StringBuilder.appendIptablesVariantCleanupRules(
    config: RootIptablesConfig,
    variant: TproxyIptablesVariant,
) {
    appendDeleteRuleLoop(variant.command, "PREROUTING", "-j ${variant.preroutingChain}")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-j ${variant.outputChain}")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-p tcp -j ${variant.outputChain}")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-p udp -j ${variant.outputChain}")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-p tcp -j ${variant.dnsOutputChain}", table = "nat")
    appendDeleteRuleLoop(variant.command, "OUTPUT", "-p udp -j ${variant.dnsOutputChain}", table = "nat")
    listOf(variant.preroutingChain, variant.outputChain).forEach { chain ->
        appendScript(
            """
            ${variant.command} -t mangle -F $chain 2>/dev/null || true
            ${variant.command} -t mangle -X $chain 2>/dev/null || true
            """,
        )
    }
    appendIpRuleDeleteLoop(
        ipCommand = variant.ipCommand,
        rule = "priority $RootProxyRouteRulePriority fwmark ${config.mark} table ${variant.routeTable}",
    )
    appendScript(
        """
        ${variant.command} -t nat -F ${variant.dnsOutputChain} 2>/dev/null || true
        ${variant.command} -t nat -X ${variant.dnsOutputChain} 2>/dev/null || true
        ${variant.ipCommand} route flush table ${variant.routeTable} 2>/dev/null || true
        """,
    )
    variant.dummyInterface?.let { dummyInterface ->
        appendDummyCleanupRules(variant.command, variant.ipCommand, dummyInterface)
    }
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

private fun StringBuilder.appendPreroutingDnsTproxyRules(
    variant: TproxyIptablesVariant,
    port: Int,
    mark: String,
) {
    appendScript(
        "${variant.command} -t mangle -A ${variant.preroutingChain} -p udp -m udp --dport 53 " +
            "-j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark",
    )
}

private fun StringBuilder.appendPreroutingMarkedTproxyRules(
    variant: TproxyIptablesVariant,
    port: Int,
    mark: String,
) {
    appendScript(
        """
        ${variant.command} -t mangle -A ${variant.preroutingChain} -p tcp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
        ${variant.command} -t mangle -A ${variant.preroutingChain} -p udp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
        """,
    )
}

private fun StringBuilder.appendPreroutingInterfaceTproxyRules(
    variant: TproxyIptablesVariant,
    interfaceName: String,
    port: Int,
    mark: String,
) {
    val quotedInterface = interfaceName.shellQuote()
    appendScript(
        """
        ${variant.command} -t mangle -A ${variant.preroutingChain} -i $quotedInterface -p tcp -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
        ${variant.command} -t mangle -A ${variant.preroutingChain} -i $quotedInterface -p udp -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
        """,
    )
}

private fun StringBuilder.appendPreroutingPrivateDestinationInterfaceTproxyRules(
    variant: TproxyIptablesVariant,
    interfacePrefixes: List<String>,
    port: Int,
    mark: String,
) {
    interfacePrefixes.asReversed().forEach { prefix ->
        val quotedInterface = prefix.shellQuote()
        variant.proxyPrivateCidrs.asReversed().forEach { cidr ->
            val quotedCidr = cidr.shellQuote()
            appendScript(
                """
                ${variant.command} -t mangle -A ${variant.preroutingChain} -d $quotedCidr -i $quotedInterface -p udp -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
                ${variant.command} -t mangle -A ${variant.preroutingChain} -d $quotedCidr -i $quotedInterface -p tcp -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
                """,
            )
        }
    }
}

private fun StringBuilder.appendPreroutingPrivateDestinationMarkedTproxyRules(
    variant: TproxyIptablesVariant,
    port: Int,
    mark: String,
) {
    variant.proxyPrivateCidrs.asReversed().forEach { cidr ->
        val quotedCidr = cidr.shellQuote()
        appendScript(
            """
            ${variant.command} -t mangle -A ${variant.preroutingChain} -d $quotedCidr -p udp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
            ${variant.command} -t mangle -A ${variant.preroutingChain} -d $quotedCidr -p tcp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${variant.tproxyOnIp} --tproxy-mark $mark
            """,
        )
    }
}
