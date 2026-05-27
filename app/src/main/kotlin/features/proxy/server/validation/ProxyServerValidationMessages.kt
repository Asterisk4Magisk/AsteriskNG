package features.proxy.server.validation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.R
import features.proxy.server.model.ProxyServerValidationError
import features.proxy.server.model.ProxyServerValidationException
import androidx.compose.ui.res.stringResource
import ui.text.formatTemplate

@Composable
internal fun rememberProxyServerValidationMessageResolver(
    fallbackMessage: String,
): (Throwable) -> String {
    val messages = ProxyServerValidationMessages(
        requiredField = stringResource(R.string.proxy_validation_required_field),
        requiredFieldNames = mapOf(
            "remarks" to stringResource(R.string.proxy_editor_remarks),
            "server address" to stringResource(R.string.proxy_editor_server),
            "server port" to stringResource(R.string.proxy_editor_port),
            "user ID" to stringResource(R.string.proxy_editor_user_id),
            "password" to stringResource(R.string.proxy_editor_password),
            "transport protocol" to stringResource(R.string.proxy_editor_transport_type),
            "transport security" to stringResource(R.string.proxy_editor_security),
            "header type" to stringResource(R.string.proxy_editor_header_type),
            "gRPC mode" to stringResource(R.string.proxy_editor_grpc_mode),
            "XHTTP mode" to stringResource(R.string.proxy_editor_xhttp_mode),
            "TLS fingerprint" to stringResource(R.string.proxy_editor_tls_fingerprint),
            "FinalMask" to "FinalMask",
            "XHTTP Extra" to "XHTTP Extra",
            "encryption method" to stringResource(R.string.proxy_editor_encryption),
            "strategy group type" to stringResource(R.string.proxy_editor_strategy_group_type),
            "custom JSON" to stringResource(R.string.proxy_editor_custom_json),
            "custom JSON outbounds" to stringResource(R.string.proxy_editor_custom_json_outbounds),
        ),
        serverAddressContainsInvalidContent =
            stringResource(R.string.proxy_validation_server_address_contains_invalid_content),
        invalidServerAddress = stringResource(R.string.proxy_validation_invalid_server_address),
        numberRequired = stringResource(R.string.proxy_validation_number_required),
        portOutOfRange = stringResource(R.string.proxy_validation_port_out_of_range),
        valueOutOfRange = stringResource(R.string.proxy_validation_value_out_of_range),
        unsupportedValue = stringResource(R.string.proxy_validation_unsupported_value),
        usernameRequiredForPassword = stringResource(R.string.proxy_validation_username_required_for_password),
        passwordRequiredForUsername = stringResource(R.string.proxy_validation_password_required_for_username),
        realityTransportUnsupported = stringResource(R.string.proxy_validation_reality_transport_unsupported),
        realityShortIdInvalid = stringResource(R.string.proxy_validation_reality_short_id_invalid),
        realityMldsa65VerifyInvalid = stringResource(R.string.proxy_validation_reality_mldsa65_verify_invalid),
        wireguardKeyInvalid = stringResource(R.string.proxy_validation_wireguard_key_invalid),
        wireguardReservedCountInvalid = stringResource(R.string.proxy_validation_wireguard_reserved_count_invalid),
        wireguardReservedValueInvalid = stringResource(R.string.proxy_validation_wireguard_reserved_value_invalid),
        localAddressCidrRequired = stringResource(R.string.proxy_validation_local_address_cidr_required),
        localAddressPrefixNumberRequired =
            stringResource(R.string.proxy_validation_local_address_prefix_number_required),
        invalidLocalAddressCidr = stringResource(R.string.proxy_validation_invalid_local_address_cidr),
        mtuNumberRequired = stringResource(R.string.proxy_validation_mtu_number_required),
        mtuOutOfRange = stringResource(R.string.proxy_validation_mtu_out_of_range),
        invalidPortHoppingFormat = stringResource(R.string.proxy_validation_invalid_port_hopping_format),
        portHoppingRangeInvalid = stringResource(R.string.proxy_validation_port_hopping_range_invalid),
        invalidBandwidthFormat = stringResource(R.string.proxy_validation_invalid_bandwidth_format),
        sha256Invalid = stringResource(R.string.proxy_validation_sha256_invalid),
        jsonObjectRequired = stringResource(R.string.proxy_validation_json_object_required),
        realityPublicKeyInvalid = stringResource(R.string.proxy_validation_reality_public_key_invalid),
        xrayUserIdInvalid = stringResource(R.string.proxy_validation_xray_user_id_invalid),
        invalidVlessEncryption = stringResource(R.string.proxy_validation_invalid_vless_encryption),
        vlessPaddingBoundaryInvalid = stringResource(R.string.proxy_validation_vless_padding_boundary_invalid),
        vlessPaddingFormatInvalid = stringResource(R.string.proxy_validation_vless_padding_format_invalid),
        vlessPaddingRangeInvalid = stringResource(R.string.proxy_validation_vless_padding_range_invalid),
        vlessFirstPaddingInvalid = stringResource(R.string.proxy_validation_vless_first_padding_invalid),
        vlessEncryptionVerifierInvalid =
            stringResource(R.string.proxy_validation_vless_encryption_verifier_invalid),
        pathMustStartWithSlash = stringResource(R.string.proxy_validation_path_must_start_with_slash),
        unsupportedAlpn = stringResource(R.string.proxy_validation_unsupported_alpn),
        chainProxyMemberCountInvalid = stringResource(R.string.proxy_validation_chain_proxy_member_count_invalid),
        hysteriaObfsTypeRequired = stringResource(R.string.proxy_validation_hysteria_obfs_type_required),
        hysteriaObfsUnsupported = stringResource(R.string.proxy_validation_hysteria_obfs_unsupported),
        booleanRequired = stringResource(R.string.proxy_validation_boolean_required),
        portHoppingIntervalNumberRequired =
            stringResource(R.string.proxy_validation_port_hopping_interval_number_required),
        portHoppingIntervalTooSmall = stringResource(R.string.proxy_validation_port_hopping_interval_too_small),
        shadowsocks2022KeyBase64Invalid =
            stringResource(R.string.proxy_validation_shadowsocks_2022_key_base64_invalid),
        shadowsocks2022KeyLengthInvalid =
            stringResource(R.string.proxy_validation_shadowsocks_2022_key_length_invalid),
        vlessVisionFlowUnsupported = stringResource(R.string.proxy_validation_vless_vision_flow_unsupported),
    )
    return remember(messages, fallbackMessage) {
        { error -> messages.messageOf(error, fallbackMessage) }
    }
}

private data class ProxyServerValidationMessages(
    val requiredField: String,
    val requiredFieldNames: Map<String, String>,
    val serverAddressContainsInvalidContent: String,
    val invalidServerAddress: String,
    val numberRequired: String,
    val portOutOfRange: String,
    val valueOutOfRange: String,
    val unsupportedValue: String,
    val usernameRequiredForPassword: String,
    val passwordRequiredForUsername: String,
    val realityTransportUnsupported: String,
    val realityShortIdInvalid: String,
    val realityMldsa65VerifyInvalid: String,
    val wireguardKeyInvalid: String,
    val wireguardReservedCountInvalid: String,
    val wireguardReservedValueInvalid: String,
    val localAddressCidrRequired: String,
    val localAddressPrefixNumberRequired: String,
    val invalidLocalAddressCidr: String,
    val mtuNumberRequired: String,
    val mtuOutOfRange: String,
    val invalidPortHoppingFormat: String,
    val portHoppingRangeInvalid: String,
    val invalidBandwidthFormat: String,
    val sha256Invalid: String,
    val jsonObjectRequired: String,
    val realityPublicKeyInvalid: String,
    val xrayUserIdInvalid: String,
    val invalidVlessEncryption: String,
    val vlessPaddingBoundaryInvalid: String,
    val vlessPaddingFormatInvalid: String,
    val vlessPaddingRangeInvalid: String,
    val vlessFirstPaddingInvalid: String,
    val vlessEncryptionVerifierInvalid: String,
    val pathMustStartWithSlash: String,
    val unsupportedAlpn: String,
    val chainProxyMemberCountInvalid: String,
    val hysteriaObfsTypeRequired: String,
    val hysteriaObfsUnsupported: String,
    val booleanRequired: String,
    val portHoppingIntervalNumberRequired: String,
    val portHoppingIntervalTooSmall: String,
    val shadowsocks2022KeyBase64Invalid: String,
    val shadowsocks2022KeyLengthInvalid: String,
    val vlessVisionFlowUnsupported: String,
) {
    fun messageOf(error: Throwable, fallbackMessage: String): String {
        val validationError = error as? ProxyServerValidationException ?: return fallbackMessage
        val values = validationError.values
        return when (validationError.error) {
            ProxyServerValidationError.RequiredField ->
                requiredField.formatTemplate("field" to requiredFieldName(values.valueAt(0)))
            ProxyServerValidationError.ServerAddressContainsInvalidContent -> serverAddressContainsInvalidContent
            ProxyServerValidationError.InvalidServerAddress -> invalidServerAddress
            ProxyServerValidationError.NumberRequired -> numberRequired
            ProxyServerValidationError.PortOutOfRange -> portOutOfRange.formatRange(values)
            ProxyServerValidationError.ValueOutOfRange -> valueOutOfRange.formatRange(values)
            ProxyServerValidationError.UnsupportedValue -> unsupportedValue.formatTemplate("value" to values.valueAt(0))
            ProxyServerValidationError.UsernameRequiredForPassword -> usernameRequiredForPassword
            ProxyServerValidationError.PasswordRequiredForUsername -> passwordRequiredForUsername
            ProxyServerValidationError.RealityTransportUnsupported -> realityTransportUnsupported
            ProxyServerValidationError.RealityShortIdInvalid -> realityShortIdInvalid
            ProxyServerValidationError.RealityMldsa65VerifyInvalid -> realityMldsa65VerifyInvalid
            ProxyServerValidationError.WireguardKeyInvalid -> wireguardKeyInvalid
            ProxyServerValidationError.WireguardReservedCountInvalid -> wireguardReservedCountInvalid
            ProxyServerValidationError.WireguardReservedValueInvalid -> wireguardReservedValueInvalid.formatRange(values)
            ProxyServerValidationError.LocalAddressCidrRequired -> localAddressCidrRequired
            ProxyServerValidationError.LocalAddressPrefixNumberRequired -> localAddressPrefixNumberRequired
            ProxyServerValidationError.InvalidLocalAddressCidr -> invalidLocalAddressCidr
            ProxyServerValidationError.MtuNumberRequired -> mtuNumberRequired
            ProxyServerValidationError.MtuOutOfRange -> mtuOutOfRange.formatRange(values)
            ProxyServerValidationError.InvalidPortHoppingFormat -> invalidPortHoppingFormat
            ProxyServerValidationError.PortHoppingRangeInvalid -> portHoppingRangeInvalid
            ProxyServerValidationError.InvalidBandwidthFormat -> invalidBandwidthFormat
            ProxyServerValidationError.Sha256Invalid -> sha256Invalid
            ProxyServerValidationError.JsonObjectRequired ->
                jsonObjectRequired.formatTemplate("field" to requiredFieldName(values.valueAt(0)))
            ProxyServerValidationError.RealityPublicKeyInvalid -> realityPublicKeyInvalid
            ProxyServerValidationError.XrayUserIdInvalid -> xrayUserIdInvalid.formatTemplate("max" to values.valueAt(0))
            ProxyServerValidationError.InvalidVlessEncryption -> invalidVlessEncryption
            ProxyServerValidationError.VlessPaddingBoundaryInvalid -> vlessPaddingBoundaryInvalid
            ProxyServerValidationError.VlessPaddingFormatInvalid -> vlessPaddingFormatInvalid
            ProxyServerValidationError.VlessPaddingRangeInvalid -> vlessPaddingRangeInvalid
            ProxyServerValidationError.VlessFirstPaddingInvalid -> vlessFirstPaddingInvalid
            ProxyServerValidationError.VlessEncryptionVerifierInvalid -> vlessEncryptionVerifierInvalid
            ProxyServerValidationError.PathMustStartWithSlash -> pathMustStartWithSlash
            ProxyServerValidationError.UnsupportedAlpn -> unsupportedAlpn.formatTemplate("value" to values.valueAt(0))
            ProxyServerValidationError.ChainProxyMemberCountInvalid -> chainProxyMemberCountInvalid
            ProxyServerValidationError.HysteriaObfsTypeRequired -> hysteriaObfsTypeRequired
            ProxyServerValidationError.HysteriaObfsUnsupported -> hysteriaObfsUnsupported
            ProxyServerValidationError.BooleanRequired -> booleanRequired
            ProxyServerValidationError.PortHoppingIntervalNumberRequired -> portHoppingIntervalNumberRequired
            ProxyServerValidationError.PortHoppingIntervalTooSmall -> portHoppingIntervalTooSmall
            ProxyServerValidationError.Shadowsocks2022KeyBase64Invalid -> shadowsocks2022KeyBase64Invalid
            ProxyServerValidationError.Shadowsocks2022KeyLengthInvalid ->
                shadowsocks2022KeyLengthInvalid.formatTemplate("lengths" to values.valueAt(0))
            ProxyServerValidationError.VlessVisionFlowUnsupported -> vlessVisionFlowUnsupported
        }
    }

    private fun requiredFieldName(name: String): String {
        return requiredFieldNames[name].orEmpty().ifBlank { name }
    }
}

private fun String.formatRange(values: List<String>): String {
    return formatTemplate(
        "min" to values.valueAt(0),
        "max" to values.valueAt(1),
    )
}

private fun List<String>.valueAt(index: Int): String {
    return getOrNull(index).orEmpty()
}
