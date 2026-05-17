package features.routing.model

import kotlinx.serialization.Serializable

@Serializable
data class RouteRule(
    val id: Int = 0,
    val remarks: String = "",
    val outboundTag: String = "proxy",
    val domain: List<String> = emptyList(),
    val ip: List<String> = emptyList(),
    val process: List<String> = emptyList(),
    val port: String = "",
    val protocol: String = "",
    val network: String = "",
    val enabled: Boolean = true,
)
