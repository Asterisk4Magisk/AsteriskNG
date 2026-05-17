package engine.xray

import android.content.Context
import features.logs.androidCoreLogAccessFile
import features.logs.androidCoreLogErrorFile
import org.json.JSONArray
import org.json.JSONObject

internal data class XrayCoreLogPaths(
    val accessLogPath: String,
    val errorLogPath: String,
)

internal object XrayTags {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val DNS_OUT = "dns-out"
    const val REMOTE_DNS = "dns-remote"
    const val DOMESTIC_DNS = "dns-domestic"
    const val LOCAL_SOCKS_INBOUND = "socks-in"
    const val VPN_APPEND_HTTP_INBOUND = "vpn-http-in"
    const val TPROXY_INBOUND = "tproxy-in"
    const val TPROXY_SOCKS_INBOUND = "tproxy-socks-in"
    const val TPROXY_HTTP_INBOUND = "tproxy-http-in"
    const val FRAGMENT = "fragment"
    const val TUN_INBOUND = "tun-in"

    val FIXED_OUTBOUND_TAGS = setOf(
        PROXY,
        DIRECT,
        BLOCK,
        DNS_OUT,
        FRAGMENT,
    )
}

internal object XrayProtocols {
    const val TUN = "tun"
    const val DNS = "dns"
    const val FREEDOM = "freedom"
    const val BLACKHOLE = "blackhole"
    const val SOCKS = "socks"
    const val HTTP = "http"
    const val DOKODEMO_DOOR = "dokodemo-door"
}

internal fun xraySniffingDestOverrides(enableFakeDns: Boolean): List<String> {
    return buildList {
        add("http")
        add("tls")
        add("quic")
        if (enableFakeDns) {
            add("fakedns")
        }
    }
}

internal fun Context.prepareXrayCoreLogPaths(): XrayCoreLogPaths {
    return XrayCoreLogPaths(
        accessLogPath = androidCoreLogAccessFile().absolutePath,
        errorLogPath = androidCoreLogErrorFile().absolutePath,
    )
}

internal fun Iterable<String>.toJsonStringArray(): JSONArray {
    return JSONArray().also { array ->
        forEach { item -> array.put(item) }
    }
}

internal fun Iterable<JSONObject>.toJsonObjectArray(): JSONArray {
    return JSONArray().also { array ->
        forEach { item -> array.put(item) }
    }
}
