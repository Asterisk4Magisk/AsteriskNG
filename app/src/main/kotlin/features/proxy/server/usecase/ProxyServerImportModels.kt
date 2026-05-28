package features.proxy.server.usecase

import features.proxy.server.model.ProxyServer

internal data class ProxyServerImportResult(
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
)

internal enum class ProxyServerImportSource(
    val logName: String,
    val decodeBase64: Boolean,
) {
    Clipboard(logName = "clipboard", decodeBase64 = false),
    File(logName = "file", decodeBase64 = true),
    QrCode(logName = "qr_code", decodeBase64 = false),
    SubscriptionUrl(logName = "subscription_url", decodeBase64 = true),
}

internal typealias ProxyServerPayloadParser = (String, ProxyServerImportSource) -> ProxyServerImportResult

internal val EmptyProxyServerImportResult = ProxyServerImportResult(
    urlCount = 0,
    servers = emptyList(),
)
