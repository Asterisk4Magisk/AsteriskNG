// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.xray.XrayFakeDnsIpv4Pool
import utils.shellQuote

internal fun StringBuilder.appendScript(script: String) {
    append(script.trimIndent())
    append('\n')
}

internal fun StringBuilder.appendHeredoc(
    targetPath: String,
    content: String,
) {
    appendScript("cat > ${targetPath.shellQuote()} <<'$BootScriptHeredocDelimiter'")
    append(content)
    if (!content.endsWith('\n')) {
        append('\n')
    }
    appendScript(BootScriptHeredocDelimiter)
}

internal fun StringBuilder.appendDeleteRuleLoop(
    command: String,
    chain: String,
    rule: String,
    table: String = "mangle",
) {
    appendScript("while $command -t $table -D $chain $rule 2>/dev/null; do :; done")
}

internal fun StringBuilder.appendIpRuleDeleteLoop(
    ipCommand: String,
    rule: String,
) {
    appendScript("while $ipCommand rule del $rule 2>/dev/null; do :; done")
}

internal fun StringBuilder.appendRootIpv6DnsRejectRules() {
    appendRootIpv6DnsRejectCleanupRules()
    appendScript(
        """
        $RootIp6tablesCommand -t mangle -I PREROUTING 1 -p udp --dport 53 -j DROP
        $RootIp6tablesCommand -t filter -I INPUT 1 -p udp --dport 53 -j REJECT
        $RootIp6tablesCommand -t filter -I FORWARD 1 -p udp --dport 53 -j REJECT
        $RootIp6tablesCommand -t filter -I OUTPUT 1 -p udp --dport 53 -j REJECT
        """,
    )
}

internal fun StringBuilder.appendRootIpv6DnsRejectCleanupRules() {
    appendDeleteRuleLoop(
        command = RootIp6tablesCommand,
        chain = "PREROUTING",
        rule = "-p udp --dport 53 -j DROP",
        table = "mangle",
    )
    appendDeleteRuleLoop(
        command = RootIp6tablesCommand,
        chain = "INPUT",
        rule = "-p udp --dport 53 -j REJECT",
        table = "filter",
    )
    appendDeleteRuleLoop(
        command = RootIp6tablesCommand,
        chain = "FORWARD",
        rule = "-p udp --dport 53 -j REJECT",
        table = "filter",
    )
    appendDeleteRuleLoop(
        command = RootIp6tablesCommand,
        chain = "OUTPUT",
        rule = "-p udp --dport 53 -j REJECT",
        table = "filter",
    )
}

internal fun StringBuilder.appendRootFakeDnsIcmpReplyRules() {
    appendRootFakeDnsIcmpReplyCleanupRules()
    val pool = XrayFakeDnsIpv4Pool.shellQuote()
    appendScript(
        """
        $RootIptablesCommand -t nat -N $RootFakeDnsIcmpReplyChain 2>/dev/null || true
        $RootIptablesCommand -t nat -N $RootFakeDnsIcmpReplyPreroutingChain 2>/dev/null || true
        $RootIptablesCommand -t nat -I OUTPUT 1 -j $RootFakeDnsIcmpReplyChain
        $RootIptablesCommand -t nat -I PREROUTING 1 -j $RootFakeDnsIcmpReplyPreroutingChain
        $RootIptablesCommand -t nat -A $RootFakeDnsIcmpReplyChain -d $pool -p icmp --icmp-type echo-request -j REDIRECT
        $RootIptablesCommand -t nat -A $RootFakeDnsIcmpReplyPreroutingChain -d $pool -p icmp --icmp-type echo-request -j REDIRECT
        """,
    )
}

internal fun StringBuilder.appendRootFakeDnsIcmpReplyCleanupRules() {
    appendDeleteRuleLoop(RootIptablesCommand, "OUTPUT", "-j $RootFakeDnsIcmpReplyChain", table = "nat")
    appendDeleteRuleLoop(RootIptablesCommand, "PREROUTING", "-j $RootFakeDnsIcmpReplyPreroutingChain", table = "nat")
    appendScript(
        """
        $RootIptablesCommand -t nat -F $RootFakeDnsIcmpReplyChain 2>/dev/null || true
        $RootIptablesCommand -t nat -X $RootFakeDnsIcmpReplyChain 2>/dev/null || true
        $RootIptablesCommand -t nat -F $RootFakeDnsIcmpReplyPreroutingChain 2>/dev/null || true
        $RootIptablesCommand -t nat -X $RootFakeDnsIcmpReplyPreroutingChain 2>/dev/null || true
        """,
    )
}
