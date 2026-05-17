package engine.tproxy

import engine.network.NetworkLimits

const val DefaultTproxyPort = NetworkLimits.PORT_MAX
const val DefaultTproxySocks5Port = NetworkLimits.PORT_MAX - 1
const val DefaultTproxyHttpPort = NetworkLimits.PORT_MAX - 2
