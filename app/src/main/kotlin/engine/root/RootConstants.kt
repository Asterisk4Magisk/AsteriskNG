// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.network.NetworkDefaults
import engine.network.NetworkLimits

const val RootXrayUid = 0
const val RootXrayGid = 3005
const val RootIptablesCommand = "iptables"
const val RootIp6tablesCommand = "ip6tables"
const val RootIpCommand = "ip"
const val RootIp6Command = "ip -6"
const val RootSharedProxyListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS
const val RootProxyRouteRulePriority = 14599
const val RootFakeDnsIcmpReplyChain = "ASTERISK_FAKE_DNS_ICMP"
const val RootFakeDnsIcmpReplyPreroutingChain = "ASTERISK_FAKE_DNS_ICMP_PRE"
const val RootStartupScriptFileName = "startup.sh"
const val RootBootLogFileName = "boot.log"
const val RootConfigFileName = "config-root.json"
const val RootPidFileName = "xray-root.pid"
const val RootIpv6DisablerPidFileName = "ipv6disabler.pid"
const val RootIpv6DisablerLogFileName = "ipv6disabler.log"
const val BootScriptHeredocDelimiter = "ASTERISKNG_BOOT_SCRIPT"
const val RootBootScriptDir = "/data/adb/service.d"
const val RootBootScriptPath = "$RootBootScriptDir/asteriskng_start.sh"
const val DefaultRootHttpProxyPort = NetworkLimits.PORT_MAX - 2

val RootProxyAppWhitelistSystemUids = listOf(0, 1052)

val RootDefaultBypassPrivateCidrs = listOf(
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.0.0.0/8",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "192.0.0.0/24",
    "192.0.2.0/24",
    "192.88.99.0/24",
    "192.168.0.0/16",
    "198.51.100.0/24",
    "203.0.113.0/24",
    "224.0.0.0/4",
    "240.0.0.0/4",
    "255.255.255.255/32",
    "::/128",
    "::1/128",
    "::ffff:0:0/96",
    "100::/64",
    "64:ff9b::/96",
    "2001::/32",
    "2001:10::/28",
    "2001:20::/28",
    "2001:db8::/32",
    "2002::/16",
    "fe80::/10",
    "ff00::/8",
)
