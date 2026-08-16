// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.config

import features.settings.servicecontrol.ServiceCronParseResult
import features.settings.servicecontrol.isValidServiceSsid
import features.settings.servicecontrol.normalizeBssidOrNull
import features.settings.servicecontrol.parseServiceCron

internal object AsteriskdConfigValidator {
    fun validate(config: AsteriskdConfig) = with(config) {
        require(owner == AsteriskdOwner.AsteriskNg && coreType == AsteriskdCoreType.Xray)
        AsteriskdNgConfigFactory.requireRunnableMode(mode)
        require(!(network.enableIpv6 && network.disableSystemIpv6))
        require(network.enableFakeDns == (network.fakeDnsIpv4Pool != null))
        require(network.appPolicy.uids == network.appPolicy.uids.distinct().sorted())
        require(network.appPolicy.bypassUids == network.appPolicy.bypassUids.distinct().sorted())
        require((network.appPolicy.directCidrPathV4 == null) == (network.appPolicy.directCidrPathV6 == null))
        validateServiceControl(serviceControl)
        when (mode) {
            AsteriskdMode.Tproxy -> {
                require(modeOptions.transparentPort != null && modeOptions.tunnelName == null)
                require(helper == null)
            }
            AsteriskdMode.Tun2Socks -> {
                require(modeOptions.transparentPort == null && modeOptions.tunnelName == null)
                require(helper is AsteriskdHevSocks5TunnelHelper)
            }
            AsteriskdMode.Bpf2Socks -> {
                require(modeOptions.transparentPort == null && modeOptions.tunnelName == null)
                require(helper is AsteriskdBpf2SocksHelper && matcher == null)
            }
            AsteriskdMode.Tun,
            AsteriskdMode.Ebpf,
            -> error("Unsupported AsteriskNG runtime mode")
        }
        network.appPolicy.directCidrPathV4?.let { pathV4 ->
            require(pathV4.isNotBlank() && !network.appPolicy.directCidrPathV6.isNullOrBlank())
            require((matcher != null) xor (mode == AsteriskdMode.Bpf2Socks))
        }
    }

    private fun validateServiceControl(value: AsteriskdServiceControlConfig) {
        require(!(value.wifi.connectStart.enabled && value.wifi.connectStop.enabled))
        require(!(value.wifi.disconnectStart.enabled && value.wifi.disconnectStop.enabled))
        if (value.enabled && value.schedule.enabled) {
            require(parseServiceCron(value.schedule.startCron) is ServiceCronParseResult.Valid)
            require(parseServiceCron(value.schedule.stopCron) is ServiceCronParseResult.Valid)
        }
        listOf(
            value.wifi.connectStart,
            value.wifi.connectStop,
            value.wifi.disconnectStart,
            value.wifi.disconnectStop,
        ).forEach(::validateWifiRule)
    }

    private fun validateWifiRule(value: AsteriskdWifiRule) {
        require(value.ssids.size <= 64 && value.ssids == value.ssids.distinct())
        require(value.ssids.all(::isValidServiceSsid))
        require(value.bssids.size <= 64 && value.bssids == value.bssids.distinct())
        require(value.bssids.all { bssid -> normalizeBssidOrNull(bssid) == bssid })
    }
}

internal object AsteriskdNgConfigFactory {
    fun requireRunnableMode(mode: AsteriskdMode) {
        require(mode == AsteriskdMode.Tproxy || mode == AsteriskdMode.Tun2Socks || mode == AsteriskdMode.Bpf2Socks) {
            "AsteriskNG does not support ${mode.wireValue} as a standalone ROOT mode"
        }
    }
}
