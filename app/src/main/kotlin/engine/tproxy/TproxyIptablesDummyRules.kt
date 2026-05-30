// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

internal fun StringBuilder.appendDummyRouteRules(family: TproxyIptablesFamily) {
    val dummy = family.dummy ?: return
    appendScript(
        """
        ${family.ipCommand} link add ${dummy.device.shellQuote()} type dummy 2>/dev/null || true
        ${family.ipCommand} addr add ${dummy.address.shellQuote()} dev ${dummy.device.shellQuote()} 2>/dev/null || true
        ${family.ipCommand} link set ${dummy.device.shellQuote()} up 2>/dev/null || true
        ${family.ipCommand} rule add not from all fwmark ${dummy.mark} table ${dummy.routeTable} 2>/dev/null || true
        ${family.ipCommand} route add local default dev ${dummy.device.shellQuote()} table ${dummy.routeTable} 2>/dev/null || true
        """,
    )
}

internal fun StringBuilder.appendDummyPreroutingRules(
    command: String,
    dummy: TproxyDummyIptablesConfig,
    port: Int,
) {
    appendScript(
        """
        $command -t mangle -N ${dummy.preroutingChain} 2>/dev/null || true
        $command -t mangle -A ${dummy.preroutingChain} -i ${dummy.device.shellQuote()} -p tcp -j TPROXY --on-ip :: --on-port $port --tproxy-mark ${dummy.mark}
        $command -t mangle -A ${dummy.preroutingChain} -i ${dummy.device.shellQuote()} -p udp -j TPROXY --on-ip :: --on-port $port --tproxy-mark ${dummy.mark}
        $command -t mangle -A PREROUTING -j ${dummy.preroutingChain}
        """,
    )
}

internal fun StringBuilder.appendDummyOutputRules(
    command: String,
    dummy: TproxyDummyIptablesConfig,
) {
    appendScript(
        """
        $command -t mangle -N ${dummy.outputChain} 2>/dev/null || true
        $command -t mangle -A ${dummy.outputChain} -p tcp -j MARK --set-xmark ${dummy.mark}
        $command -t mangle -A ${dummy.outputChain} -p udp -j MARK --set-xmark ${dummy.mark}
        $command -t mangle -A OUTPUT -j ${dummy.outputChain}
        """,
    )
}

internal fun StringBuilder.appendDummyCleanupRules(
    command: String,
    ipCommand: String,
    dummy: TproxyDummyIptablesConfig,
) {
    appendDeleteJumpLoop(command, "OUTPUT", "-j ${dummy.outputChain}")
    appendDeleteJumpLoop(command, "PREROUTING", "-j ${dummy.preroutingChain}")
    listOf(dummy.outputChain, dummy.preroutingChain).forEach { chain ->
        appendScript(
            """
            $command -t mangle -F $chain 2>/dev/null || true
            $command -t mangle -X $chain 2>/dev/null || true
            """,
        )
    }
    appendScript(
        """
        $ipCommand rule del not from all fwmark ${dummy.mark} table ${dummy.routeTable} 2>/dev/null || true
        $ipCommand route del local default dev ${dummy.device.shellQuote()} table ${dummy.routeTable} 2>/dev/null || true
        $ipCommand link set ${dummy.device.shellQuote()} down 2>/dev/null || true
        $ipCommand link del ${dummy.device.shellQuote()} type dummy 2>/dev/null || true
        """,
    )
}
