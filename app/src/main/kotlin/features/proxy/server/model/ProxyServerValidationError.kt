package features.proxy.server.model

class ProxyServerValidationException(
    val error: ProxyServerValidationError,
    val values: List<String> = emptyList(),
) : IllegalArgumentException(error.name)

enum class ProxyServerValidationError {
    RequiredField,
    ServerAddressContainsInvalidContent,
    InvalidServerAddress,
    NumberRequired,
    PortOutOfRange,
    ValueOutOfRange,
    UnsupportedValue,
    UsernameRequiredForPassword,
    PasswordRequiredForUsername,
    RealityTransportUnsupported,
    RealityShortIdInvalid,
    RealityMldsa65VerifyInvalid,
    WireguardKeyInvalid,
    WireguardReservedCountInvalid,
    WireguardReservedValueInvalid,
    LocalAddressCidrRequired,
    LocalAddressPrefixNumberRequired,
    InvalidLocalAddressCidr,
    MtuNumberRequired,
    MtuOutOfRange,
    InvalidPortHoppingFormat,
    PortHoppingRangeInvalid,
    InvalidBandwidthFormat,
    Sha256Invalid,
    JsonObjectRequired,
    RealityPublicKeyInvalid,
    XrayUserIdInvalid,
    InvalidVlessEncryption,
    VlessPaddingBoundaryInvalid,
    VlessPaddingFormatInvalid,
    VlessPaddingRangeInvalid,
    VlessFirstPaddingInvalid,
    VlessEncryptionVerifierInvalid,
    PathMustStartWithSlash,
    UnsupportedAlpn,
    ChainProxyMemberCountInvalid,
    HysteriaObfsTypeRequired,
    HysteriaObfsUnsupported,
    BooleanRequired,
    PortHoppingIntervalNumberRequired,
    PortHoppingIntervalTooSmall,
    PluginNameRequired,
    Shadowsocks2022KeyBase64Invalid,
    Shadowsocks2022KeyLengthInvalid,
    VlessVisionFlowUnsupported,
}

internal fun proxyValidationError(
    error: ProxyServerValidationError,
    vararg values: Any?,
): Nothing {
    throw ProxyServerValidationException(
        error = error,
        values = values.map { value -> value?.toString().orEmpty() },
    )
}
