[Русский](README_ru.md) | [English](README.md) | 简体中文

# AsteriskNG

一个 Android Xray GUI 客户端，使用 [Xray-core](https://github.com/XTLS/Xray-core)、[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)、[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 实现。

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## 功能

- VPN Service、TPROXY(ROOT)、TUN2SOCKS(ROOT) 和 BPF2SOCKS(ROOT) 运行模式
- VMess、VLESS、Trojan、Shadowsocks、SOCKS、HTTP、Hysteria2、WireGuard、策略组和链式代理
- 支持 v2rayNG 和 Mihomo 订阅格式
- 配置、代理、路由、日志和资源管理
- MIUIX Compose UI

## 预览

<p align="center">
  <img src="image/screenshot/1.jpg" width="24%" alt="截图 1" />
  <img src="image/screenshot/2.jpg" width="24%" alt="截图 2" />
  <img src="image/screenshot/3.jpg" width="24%" alt="截图 3" />
  <img src="image/screenshot/4.jpg" width="24%" alt="截图 4" />
</p>

## 运行模式

### VPN Service

- 无需 root 权限。
- 使用 Android `VpnService`。
- 通过 AndroidLibXrayLite 在应用进程中运行 Xray。

### TPROXY(ROOT)

- 通过 libsu 直接运行本地 Xray 可执行文件。
- 使用 TPROXY 入站、iptables 和策略路由处理透明代理流量。

### TUN2SOCKS(ROOT)

- 通过 libsu 直接运行本地 Xray 可执行文件。
- 使用 `hev-socks5-tunnel` 创建固定 TUN 设备 `asterisk0`。
- 将隧道流量送入本地 Xray SOCKS5 入站。

### BPF2SOCKS(ROOT)

- 通过 libsu 直接运行本地 Xray 可执行文件和 native `bpf2socks` helper。
- 使用 eBPF 接管 TCP、UDP 流量并送入本地 Xray SOCKS5 入站，不创建 TUN 设备。
- 默认 bridge 端口为 `65532`，SOCKS5 入站端口为 `65534`。
- 启动前要求 eBPF 能力探测通过。设备支持不足时，该模式无法启动。

### asteriskd

- 监听本地 IPv4/IPv6 地址和热点接口变化，并刷新相应的 iptables 规则或 BPF map。
- 服务停止时清理当前 ROOT 模式负责的网络规则。

## 资源文件

- 运行文件存储在应用私有的 `files/xray` 目录。
- 内置 Xray 可执行文件可替换为可执行文件或包含 `xray` 的 zip 压缩包。
- `geoip.dat`、`geosite.dat` 等资源可恢复、在本地替换，或通过内置及自定义来源更新。

## 开发

构建前初始化 submodule：

```bash
git submodule update --init --recursive
```

使用 Android Studio 打开项目根目录，或通过 Gradle wrapper 构建：

```powershell
.\gradlew.bat assembleDebug
```

macOS 或 Linux：

```bash
./gradlew assembleDebug
```

构建会准备 Xray，构建已配置的 native helper submodule，并打包支持的 ABI。

如果 Gradle 找不到 Android NDK，请通过 Android Studio、`local.properties` 中的 `ndk.dir` 或 `ANDROID_NDK_HOME` 配置。

## WSA

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```

## 许可

[GPL-3.0](LICENSE)

## 致谢

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
