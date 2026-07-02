// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.network.NetworkDefaults
import engine.network.NetworkLimits

const val RootXrayUid = 0
const val RootXrayGid = 3005
const val RootNetworkStackUid = 1073
const val RootIptablesCommand = "iptables -w 100"
const val RootIp6tablesCommand = "ip6tables -w 100"
const val RootIpCommand = "ip"
const val RootIp6Command = "ip -6"
const val RootSharedProxyListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS
const val RootProxyRouteRulePriority = 14599
const val RootFakeDnsIcmpReplyChain = "ASTERISK_FAKE_DNS_ICMP"
const val RootFakeDnsIcmpReplyPreroutingChain = "ASTERISK_FAKE_DNS_ICMP_PRE"
const val RootStartupScriptFileName = "startup.sh"
const val RootBootLogFileName = "boot.log"
const val RootEbpfPolicyFileName = "bpf-policy.json"
const val RootEbpfDirectCidrV4FileName = "direct-cidr-v4.txt"
const val RootEbpfDirectCidrV6FileName = "direct-cidr-v6.txt"
const val RootBpf2SocksConfigFileName = "bpf2socks.json"
const val RootBpf2SocksPidFileName = "bpf2socks.pid"
const val RootEbpfPinnedObjectDir = "/sys/fs/bpf/asteriskng"
const val RootBpf2SocksPinnedObjectDir = "/sys/fs/bpf/asteriskng/bpf2socks"
const val RootBpf2SocksCgroupPath = "/sys/fs/cgroup"
const val RootBpf2SocksTokenIpv4 = "127.79.0.1"
const val RootBpf2SocksTokenIpv6Prefix = "fd7a:7374:6572:6973::/64"
const val RootBpf2SocksListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS
const val RootBpf2SocksSocksInboundAddress = "127.0.0.1"
const val RootBpf2SocksDefaultBridgePort = NetworkLimits.PORT_MAX - 3
const val RootBpf2SocksFwmark = "0x8000000/0x8000000"
const val RootBpf2SocksRouteTable = "172"
const val RootBpf2SocksPreroutingChain = "ASTERISK_B2S_PREROUTING"
const val RootBpf2SocksPreroutingV4ProgramPath = "$RootBpf2SocksPinnedObjectDir/prerouting_v4"
const val RootBpf2SocksPreroutingV6ProgramPath = "$RootBpf2SocksPinnedObjectDir/prerouting_v6"
const val RootEbpfXtOutputV4ProgramPath = "/sys/fs/bpf/asteriskng/xt_output_v4"
const val RootEbpfXtOutputV6ProgramPath = "/sys/fs/bpf/asteriskng/xt_output_v6"
const val RootEbpfXtPreroutingV4ProgramPath = "/sys/fs/bpf/asteriskng/xt_prerouting_v4"
const val RootEbpfXtPreroutingV6ProgramPath = "/sys/fs/bpf/asteriskng/xt_prerouting_v6"
const val RootEbpfProbeXtOutputV4ProgramPath = "/sys/fs/bpf/asteriskng/probe_xt_output_v4"
const val RootEbpfProbeXtOutputV6ProgramPath = "/sys/fs/bpf/asteriskng/probe_xt_output_v6"
const val RootEbpfProbeXtPreroutingV4ProgramPath = "/sys/fs/bpf/asteriskng/probe_xt_prerouting_v4"
const val RootEbpfProbeXtPreroutingV6ProgramPath = "/sys/fs/bpf/asteriskng/probe_xt_prerouting_v6"
const val RootConfigFileName = "config-root.json"
const val RootPidFileName = "xray-root.pid"
const val RootIpv6DisablerPidFileName = "ipv6disabler.pid"
const val RootIpv6DisablerLogFileName = "ipv6disabler.log"
const val BootScriptHeredocDelimiter = "ASTERISKNG_BOOT_SCRIPT"
const val RootBootScriptDir = "/data/adb/service.d"
const val RootBootScriptPath = "$RootBootScriptDir/asteriskng_start.sh"
const val DefaultRootHttpProxyPort = NetworkLimits.PORT_MAX - 2

val RootProxyAppWhitelistSystemUids = listOf(0, 1052)
val RootBpf2SocksSystemBypassUids = (RootProxyAppWhitelistSystemUids + RootNetworkStackUid).distinct()

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
