package engine.tproxy

import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist

internal fun TproxyIptablesConfig.setupCommand(
    port: Int,
    enableIpv6: Boolean,
): String {
    return buildString {
        appendSetupRules(
            config = this@setupCommand,
            family = ipv4Family(),
            port = port,
        )
        if (enableIpv6) {
            appendIpv6SetupRules(this@setupCommand, port)
        } else {
            appendDisableIpv6DnsRule(TproxyIp6tablesCommand)
        }
    }
}

internal fun TproxyIptablesConfig.cleanupCommand(): String {
    return buildString {
        appendCleanupRules(this@cleanupCommand, ipv4Family())
        appendDeleteJumpLoop(TproxyIp6tablesCommand, "OUTPUT", "-p udp --dport 53 -j REJECT", table = "filter")
        appendCleanupRules(this@cleanupCommand, ipv6Family(dummy = false))
        appendCleanupRules(this@cleanupCommand, ipv6Family(dummy = true))
    }
}

private fun StringBuilder.appendIpv6SetupRules(
    config: TproxyIptablesConfig,
    port: Int,
) {
    appendScript("if ${hasGlobalIpv6AddressTest()}; then")
    appendSetupRules(config, config.ipv6Family(dummy = false), port)
    appendScript("else")
    appendSetupRules(config, config.ipv6Family(dummy = true), port)
    appendScript("fi")
}

private fun StringBuilder.appendSetupRules(
    config: TproxyIptablesConfig,
    family: TproxyIptablesFamily,
    port: Int,
) {
    if (family.dummy == null) {
        appendScript(
            """
            ${family.ipCommand} rule add fwmark ${config.mark} table ${family.routeTable} 2>/dev/null || true
            ${family.ipCommand} route add local ${family.routeDestination} dev lo table ${family.routeTable} 2>/dev/null || true
            """,
        )
    } else {
        appendDummyRouteRules(family)
    }
    appendScript(
        """
        ${family.command} -t mangle -N ${family.preroutingTargetChain} 2>/dev/null || true
        ${family.command} -t mangle -N ${family.outputChain} 2>/dev/null || true
        ${family.command} -t mangle -I PREROUTING 1 -j ${family.preroutingTargetChain}
        ${family.command} -t mangle -I OUTPUT 1 -j ${family.outputChain}
        """,
    )
    appendPreroutingDnsHijackRules(family, port, config.mark)
    appendPreroutingProxyInterfaceRules(
        family = family,
        interfacePrefixes = config.externalInterfacePrefixes,
        port = port,
        mark = config.mark,
    )
    appendPreroutingProxyMarkedRules(family, port, config.mark)
    appendBypassRules(
        command = family.command,
        chain = family.preroutingTargetChain,
        cidrs = family.bypassPrivateCidrs,
        interfaces = emptyList(),
        input = true,
    )
    appendBypassRules(
        command = family.command,
        chain = family.preroutingTargetChain,
        cidrs = family.localInterfaceCidrs,
        interfaces = emptyList(),
        input = true,
    )
    appendPreroutingMarkedProxyRules(family, port, config.mark)
    config.externalInterfacePrefixes.forEach { prefix ->
        appendPreroutingInterfaceProxyRules(family, prefix, port, config.mark)
    }
    family.dummy?.let { dummy ->
        appendDummyPreroutingRules(family.command, dummy, port)
    }
    appendOutputDnsMarkRules(family.command, family.outputChain, config.gid, config.mark)
    appendOutputProxyRules(family.command, family.outputChain, family.proxyPrivateCidrs, config.mark)
    appendOutputApplicationRules(
        command = family.command,
        chain = family.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
        mark = config.mark,
        phase = TproxyOutputApplicationRulePhase.BeforeBypass,
    )
    family.dummy?.let { dummy ->
        appendScript("${family.command} -t mangle -A ${family.outputChain} -o ${dummy.device.shellQuote()} -j RETURN")
    }
    appendBypassRules(
        command = family.command,
        chain = family.outputChain,
        cidrs = emptyList(),
        interfaces = config.ignoredInterfaces,
        input = false,
    )
    appendBypassRules(
        command = family.command,
        chain = family.outputChain,
        cidrs = family.bypassPrivateCidrs,
        interfaces = emptyList(),
        input = false,
    )
    appendBypassRules(
        command = family.command,
        chain = family.outputChain,
        cidrs = family.localInterfaceCidrs,
        interfaces = emptyList(),
        input = false,
    )
    appendScript("${family.command} -t mangle -A ${family.outputChain} -m owner --gid-owner ${config.gid} -j RETURN")
    appendOutputApplicationRules(
        command = family.command,
        chain = family.outputChain,
        mode = config.proxyAppListMode,
        uids = config.proxyApplicationUids,
        mark = config.mark,
        phase = TproxyOutputApplicationRulePhase.AfterBypass,
    )
    family.dummy?.let { dummy ->
        appendDummyOutputRules(family.command, dummy)
    }
}

private fun StringBuilder.appendCleanupRules(
    config: TproxyIptablesConfig,
    family: TproxyIptablesFamily,
) {
    appendDeleteJumpLoop(family.command, "PREROUTING", "-j ${family.legacyPreroutingChain}")
    appendDeleteJumpLoop(family.command, "PREROUTING", "-j ${family.preroutingTargetChain}")
    appendDeleteJumpLoop(family.command, "OUTPUT", "-j ${family.outputChain}")
    appendDeleteJumpLoop(family.command, "OUTPUT", "-p tcp -j ${family.outputChain}")
    appendDeleteJumpLoop(family.command, "OUTPUT", "-p udp -j ${family.outputChain}")
    appendDeleteJumpLoop(family.command, "OUTPUT", "-p tcp -j ${family.dnsOutputChain}", table = "nat")
    appendDeleteJumpLoop(family.command, "OUTPUT", "-p udp -j ${family.dnsOutputChain}", table = "nat")
    listOf(family.legacyPreroutingChain, family.preroutingTargetChain, family.outputChain).forEach { chain ->
        appendScript(
            """
            ${family.command} -t mangle -F $chain 2>/dev/null || true
            ${family.command} -t mangle -X $chain 2>/dev/null || true
            """,
        )
    }
    appendScript(
        """
        ${family.command} -t nat -F ${family.dnsOutputChain} 2>/dev/null || true
        ${family.command} -t nat -X ${family.dnsOutputChain} 2>/dev/null || true
        ${family.ipCommand} rule del fwmark ${config.mark} table ${family.routeTable} 2>/dev/null || true
        ${family.ipCommand} route flush table ${family.routeTable} 2>/dev/null || true
        """,
    )
    family.dummy?.let { dummy ->
        appendDummyCleanupRules(family.command, family.ipCommand, dummy)
    }
}

private fun StringBuilder.appendPreroutingDnsHijackRules(
    family: TproxyIptablesFamily,
    port: Int,
    mark: String,
) {
    appendScript(
        "${family.command} -t mangle -A ${family.preroutingTargetChain} -p udp -m udp --dport 53 " +
            "-j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark",
    )
}

private fun StringBuilder.appendOutputDnsMarkRules(
    command: String,
    chain: String,
    bypassGid: Int,
    mark: String,
) {
    appendScript("$command -t mangle -A $chain -p udp -m owner ! --gid-owner $bypassGid -m udp --dport 53 -j MARK --set-xmark $mark")
}

private fun StringBuilder.appendOutputApplicationRules(
    command: String,
    chain: String,
    mode: Int,
    uids: List<Int>,
    mark: String,
    phase: TproxyOutputApplicationRulePhase,
) {
    when (mode) {
        ProxyAppListModeBlacklist -> {
            if (phase == TproxyOutputApplicationRulePhase.BeforeBypass) {
                appendOutputOwnerReturnRules(command, chain, uids)
            } else {
                appendOutputMarkAllRules(command, chain, mark)
            }
        }

        ProxyAppListModeWhitelist -> {
            if (phase == TproxyOutputApplicationRulePhase.AfterBypass) {
                appendOutputOwnerMarkRules(command, chain, uids + TproxyWhitelistSystemUids, mark)
            }
        }

        ProxyAppListModeGlobal -> {
            if (phase == TproxyOutputApplicationRulePhase.AfterBypass) {
                appendOutputMarkAllRules(command, chain, mark)
            }
        }

        else -> {
            if (phase == TproxyOutputApplicationRulePhase.AfterBypass) {
                appendOutputMarkAllRules(command, chain, mark)
            }
        }
    }
}

private enum class TproxyOutputApplicationRulePhase {
    BeforeBypass,
    AfterBypass,
}

private fun StringBuilder.appendOutputMarkAllRules(
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

private fun StringBuilder.appendOutputOwnerReturnRules(
    command: String,
    chain: String,
    uids: List<Int>,
) {
    uids.distinct().asReversed().forEach { uid ->
        appendScript("$command -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
    }
}

private fun StringBuilder.appendOutputOwnerMarkRules(
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

private fun StringBuilder.appendDisableIpv6DnsRule(command: String) {
    appendScript("$command -t filter -I OUTPUT 1 -p udp --dport 53 -j REJECT")
}

private fun StringBuilder.appendPreroutingMarkedProxyRules(
    family: TproxyIptablesFamily,
    port: Int,
    mark: String,
) {
    appendScript(
        """
        ${family.command} -t mangle -A ${family.preroutingTargetChain} -p tcp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
        ${family.command} -t mangle -A ${family.preroutingTargetChain} -p udp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
        """,
    )
}

private fun StringBuilder.appendPreroutingInterfaceProxyRules(
    family: TproxyIptablesFamily,
    interfaceName: String,
    port: Int,
    mark: String,
) {
    val quotedInterface = interfaceName.shellQuote()
    appendScript(
        """
        ${family.command} -t mangle -A ${family.preroutingTargetChain} -i $quotedInterface -p tcp -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
        ${family.command} -t mangle -A ${family.preroutingTargetChain} -i $quotedInterface -p udp -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
        """,
    )
}

private fun StringBuilder.appendPreroutingProxyInterfaceRules(
    family: TproxyIptablesFamily,
    interfacePrefixes: List<String>,
    port: Int,
    mark: String,
) {
    interfacePrefixes.asReversed().forEach { prefix ->
        val quotedInterface = prefix.shellQuote()
        family.proxyPrivateCidrs.asReversed().forEach { cidr ->
            val quotedCidr = cidr.shellQuote()
            appendScript(
                """
                ${family.command} -t mangle -A ${family.preroutingTargetChain} -d $quotedCidr -i $quotedInterface -p udp -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
                ${family.command} -t mangle -A ${family.preroutingTargetChain} -d $quotedCidr -i $quotedInterface -p tcp -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
                """,
            )
        }
    }
}

private fun StringBuilder.appendPreroutingProxyMarkedRules(
    family: TproxyIptablesFamily,
    port: Int,
    mark: String,
) {
    family.proxyPrivateCidrs.asReversed().forEach { cidr ->
        val quotedCidr = cidr.shellQuote()
        appendScript(
            """
            ${family.command} -t mangle -A ${family.preroutingTargetChain} -d $quotedCidr -p udp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
            ${family.command} -t mangle -A ${family.preroutingTargetChain} -d $quotedCidr -p tcp -m mark --mark $mark -j TPROXY --on-port $port --on-ip ${family.tproxyOnIp} --tproxy-mark $mark
            """,
        )
    }
}

private fun StringBuilder.appendOutputProxyRules(
    command: String,
    chain: String,
    cidrs: List<String>,
    mark: String,
) {
    cidrs.asReversed().forEach { cidr ->
        val quotedCidr = cidr.shellQuote()
        appendScript(
            """
            $command -t mangle -A $chain -d $quotedCidr -p udp -j MARK --set-xmark $mark
            $command -t mangle -A $chain -d $quotedCidr -p tcp -j MARK --set-xmark $mark
            """,
        )
    }
}

private fun StringBuilder.appendBypassRules(
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

internal fun StringBuilder.appendDeleteJumpLoop(
    command: String,
    chain: String,
    rule: String,
    table: String = "mangle",
) {
    appendScript("while $command -t $table -D $chain $rule 2>/dev/null; do :; done")
}
