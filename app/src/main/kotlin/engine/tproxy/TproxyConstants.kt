package engine.tproxy

import engine.network.NetworkDefaults

const val TproxyBypassUid = 0
const val TproxyBypassGid = 3005
const val TproxyFwmark = "0x1000000/0x1000000"
const val TproxyRouteTable = "160"
const val TproxyDummyDevice = "xdummy"
const val TproxyDummyAddress = "fd01:5ca1:ab1e:8d97:497f:8b48:b9aa:85cd/128"
const val TproxyDummyFwmark = "0x2000000/0x2000000"
const val TproxyDummyRouteTable = "164"
const val TproxyShareListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS
const val TproxyConfigFileName = "config-tproxy.json"
const val TproxyPidFileName = "xray-tproxy.pid"
const val TproxyBootScriptDir = "/data/adb/service.d"
const val TproxyBootScriptPath = "$TproxyBootScriptDir/asteriskng_start.sh"
const val TproxyIptablesCommand = "iptables"
const val TproxyIp6tablesCommand = "ip6tables"
const val TproxyIpCommand = "ip"
const val TproxyIp6Command = "ip -6"
const val TproxyPreroutingChain = "ASTERISK_TPROXY_PREROUTING"
const val TproxyPreroutingTargetChain = "ASTERISK_TPROXY_TARGET"
const val TproxyOutputChain = "ASTERISK_TPROXY_OUTPUT"
const val TproxyDnsOutputChain = "ASTERISK_TPROXY_DNS_OUTPUT"
const val TproxyPrerouting6Chain = "ASTERISK_TPROXY6_PREROUTING"
const val TproxyPrerouting6TargetChain = "ASTERISK_TPROXY6_TARGET"
const val TproxyOutput6Chain = "ASTERISK_TPROXY6_OUTPUT"
const val TproxyDnsOutput6Chain = "ASTERISK_TPROXY6_DNS_OUTPUT"

val TproxyWhitelistSystemUids = listOf(0, 1052)

val TproxyBuiltinPrivateCidrs = listOf(
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

