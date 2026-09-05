[Русский](README_ru.md) | English | [简体中文](README_zh_CN.md)

# AsteriskNG

An Xray client for Android, powered by [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Features

- VPN Service, TPROXY(ROOT), TUN2SOCKS(ROOT), and BPF2SOCKS(ROOT) run modes
- VMess, VLESS, Trojan, Shadowsocks, SOCKS, HTTP, Hysteria2, WireGuard, strategy groups, and chained proxies
- v2rayNG and Mihomo subscription formats
- Profile, proxy, routing, log, and resource management
- MIUIX Compose UI

## Screenshots

<p align="center">
  <img src="image/screenshot/5.jpg" width="24%" alt="Screenshot 1" />
  <img src="image/screenshot/6.jpg" width="24%" alt="Screenshot 2" />
  <img src="image/screenshot/7.jpg" width="24%" alt="Screenshot 3" />
  <img src="image/screenshot/8.jpg" width="24%" alt="Screenshot 4" />
</p>

## Run Modes

### VPN Service

- Works without root permission.
- Uses Android `VpnService`.
- Runs Xray in the app process through AndroidLibXrayLite.

### TPROXY(ROOT)

- Runs the local Xray executable directly with libsu.
- Uses a TPROXY inbound with iptables and policy routing for transparent proxy traffic.

### TUN2SOCKS(ROOT)

- Runs the local Xray executable directly with libsu.
- Uses `hev-socks5-tunnel` to create the fixed TUN device `asterisk0`.
- Sends tunnel traffic to a local Xray SOCKS5 inbound.

### BPF2SOCKS(ROOT)

- Runs the local Xray executable and native `bpf2socks` helper directly with libsu.
- Uses eBPF without creating a TUN device and sends captured TCP and UDP traffic to a local Xray SOCKS5 inbound.
- Defaults to bridge port `65532` and SOCKS5 inbound port `65534`.
- Requires the eBPF capability probe to pass before startup. Devices with insufficient support cannot start this mode.

### asteriskd

- Watches local IPv4/IPv6 addresses and tethering interfaces, then refreshes the relevant iptables rules or BPF maps.
- Cleans up networking rules owned by the active ROOT mode when the service stops.

## Resource Files

- Runtime files are stored in the app-private `files/xray` directory.
- The bundled Xray executable can be replaced with an executable file or a zip archive containing `xray`.
- `geoip.dat`, `geosite.dat`, and other resources can be restored, replaced locally, or updated from built-in and custom sources.

## Development

Initialize submodules before building:

```bash
git submodule update --init --recursive
```

Open the project root in Android Studio, or build it with Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The build prepares Xray, builds the configured native helper submodules, and packages the supported ABIs.

If Gradle cannot find the Android NDK, configure it through Android Studio, `ndk.dir` in `local.properties`, or `ANDROID_NDK_HOME`.

## WSA

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```

## License

[GPL-3.0](LICENSE)

## Credits

- [@XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [@2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
- [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
- [@2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- [@Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
- [@v2fly/geoip](https://github.com/v2fly/geoip)
- [@v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
- [@Chocolate4U/Iran-v2ray-rules](https://github.com/Chocolate4U/Iran-v2ray-rules)
- [@runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
