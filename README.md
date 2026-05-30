# AsteriskNG

An Xray client for Android, powered by [Xray-core](https://github.com/XTLS/Xray-core) and [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).

### Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

### Features

- VPN Service and TPROXY(ROOT) run modes
- VMess, VLESS, Trojan, Shadowsocks, Socks, HTTP, Hysteria2, WireGuard, strategy group, and chain proxy
- Subscription groups, QR code import, latency testing, and per-app proxy
- Resource file management for `geoip.dat`, `geosite.dat`, and Xray-core
- MIUIX Compose UI

### Usage

#### Geoip and Geosite

- `geoip.dat` and `geosite.dat` are stored in the app private `files/xray` directory, commonly `/data/user/0/org.asterisk.zcc.ang/files/xray`.
- Resource files can be updated in the app from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat), [v2fly/geoip](https://github.com/v2fly/geoip) and [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community), or [Chocolate4U/Iran-v2ray-rules](https://github.com/Chocolate4U/Iran-v2ray-rules).
- Third-party `geoip.dat` and `geosite.dat` files can be imported manually from Resource management.
- Xray-core can also be replaced manually with an `xray` executable file or a zip archive containing `xray`.

#### Run Mode

- VPN Service mode works without root permission and uses Android `VpnService`.
- TPROXY mode requires ROOT permission and uses the local Xray executable with iptables rules.
- Start on boot can generate a Magisk `service.d` script for TPROXY mode.

### Development guide

Open the project root in Android Studio, or build it with Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The build downloads the bundled Xray-core asset and compiles the native `setuidgid` helper. If Gradle cannot find Android NDK, set `ndk.dir` in `local.properties`, set `ANDROID_NDK_HOME`, or install an NDK under the Android SDK.

For WSA, VPN permission can be granted with:

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```
### License

[GPL-3.0](LICENSE)

### Credits

- [@XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [@2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
- [@2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- [@Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
- [@v2fly/geoip](https://github.com/v2fly/geoip)
- [@v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
- [@Chocolate4U/Iran-v2ray-rules](https://github.com/Chocolate4U/Iran-v2ray-rules)
