Русский | [English](README.md) | [简体中文](README_zh_CN.md)

# AsteriskNG

Клиент Xray для Android, работающий на базе [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) и [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Telegram-канал

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Возможности

- Режимы VPN Service, TPROXY (ROOT), TUN2SOCKS (ROOT) и BPF2SOCKS (ROOT).
- Протоколы VMess, VLESS, Trojan, Shadowsocks, SOCKS, HTTP, Hysteria2 и WireGuard, а также группы стратегий и цепочки прокси.
- Форматы подписок v2rayNG и Mihomo.
- Управление конфигурациями, прокси, маршрутизацией, журналами и ресурсами.
- Интерфейс на базе MIUIX Compose UI.

## Скриншоты

<p align="center">
  <img src="image/screenshot/5.jpg" width="24%" alt="Скриншот 1" />
  <img src="image/screenshot/6.jpg" width="24%" alt="Скриншот 2" />
  <img src="image/screenshot/7.jpg" width="24%" alt="Скриншот 3" />
  <img src="image/screenshot/8.jpg" width="24%" alt="Скриншот 4" />
</p>

## Режимы работы

### VPN Service

- Работает без ROOT-прав.
- Использует Android `VpnService`.
- Запускает Xray в процессе приложения через AndroidLibXrayLite.

### TPROXY (ROOT)

- Запускает локальный исполняемый файл Xray напрямую через libsu.
- Использует входящее подключение TPROXY, iptables и policy routing для прозрачного проксирования трафика.

### TUN2SOCKS (ROOT)

- Запускает локальный исполняемый файл Xray напрямую через libsu.
- Использует `hev-socks5-tunnel` для создания фиксированного TUN-интерфейса `asterisk0`.
- Передаёт трафик туннеля в локальное входящее подключение SOCKS5 Xray.

### BPF2SOCKS (ROOT)

- Запускает локальный исполняемый файл Xray и нативный компонент `bpf2socks` напрямую через libsu.
- Использует eBPF без создания TUN-интерфейса и передаёт перехваченный TCP- и UDP-трафик в локальное входящее подключение SOCKS5 Xray.
- По умолчанию использует порт bridge `65532` и порт SOCKS5 `65534`.
- Перед запуском требуется успешная проверка поддержки eBPF; на неподдерживаемых устройствах режим не запускается.

### asteriskd

- Отслеживает локальные адреса IPv4/IPv6 и интерфейсы раздачи сети, затем обновляет соответствующие правила iptables или карты BPF.
- При остановке удаляет сетевые правила, принадлежащие активному ROOT-режиму.

## Файлы ресурсов

- Файлы среды выполнения хранятся в приватной директории приложения `files/xray`.
- Встроенный Xray можно заменить исполняемым файлом или zip-архивом, содержащим `xray`.
- `geoip.dat`, `geosite.dat` и другие ресурсы можно восстановить, заменить локально или обновить из встроенных и пользовательских источников.

## Разработка (Сборка)

Перед сборкой проекта инициализируйте субмодули:

```bash
git submodule update --init --recursive
```

Откройте корневую папку проекта в Android Studio или соберите проект через Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

На macOS или Linux:

```bash
./gradlew assembleDebug
```

Сборка подготавливает Xray, собирает настроенные нативные субмодули и упаковывает поддерживаемые ABI.

Если Gradle не может найти Android NDK, настройте его через Android Studio, параметр `ndk.dir` в `local.properties` или переменную `ANDROID_NDK_HOME`.

## Поддержка WSA

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```

## Лицензия

[GPL-3.0](LICENSE)

## Благодарности и используемые компоненты

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
