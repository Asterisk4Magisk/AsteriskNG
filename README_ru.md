[English](README.md) | [简体中文](README_zh_CN.md) | Русский | [Tiếng Việt](README_vi.md)

# AsteriskNG

Клиент Xray для Android с графическим интерфейсом.

## Telegram-канал

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Режимы работы

### VPN Service

- Работает без ROOT-прав.
- Использует Android `VpnService`.

### TPROXY (ROOT)

- Запускает локальный исполняемый файл Xray напрямую через libsu.
- Использует iptables и маршрутизацию на основе политик для прозрачного проксирования трафика.

### TUN2SOCKS (ROOT)

- Запускает локальный исполняемый файл Xray напрямую через libsu.
- Использует `hev-socks5-tunnel` для создания TUN-интерфейса и передачи трафика на вход SOCKS5 Xray.

### BPF2SOCKS (ROOT)

- Запускает локальный исполняемый файл Xray напрямую через libsu.
- Использует `bpf2socks` для перехвата трафика и его передачи на вход SOCKS5 Xray.
- Доступность зависит от поддержки eBPF ядром устройства.

### asteriskd

- Отслеживает локальные адреса IPv4/IPv6 и интерфейсы раздачи сети, затем обновляет соответствующие правила iptables или карты BPF.
- При остановке удаляет сетевые правила, принадлежащие активному ROOT-режиму.

## Файлы ресурсов

- Файлы для работы в режиме ROOT хранятся в приватной директории приложения `files/xray`.
- Пользовательские ресурсы можно добавлять или заменять локально, а также обновлять по заданным URL.

## Управление через broadcast

Включите **Управление через broadcast** в настройках и отправьте явный broadcast указанному ниже получателю. Префикс Action: `org.asterisk.zcc.ang.action.`.

| Действие | Суффикс Action |
| --- | --- |
| Запустить прокси | `PROXY_START` |
| Остановить прокси | `PROXY_STOP` |
| Переключить состояние прокси | `PROXY_TOGGLE` |
| Обновить все URL-подписки | `SUBSCRIPTION_UPDATE` |
| Отменить обновление подписок через broadcast | `SUBSCRIPTION_UPDATE_CANCEL` |
| Обновить все ресурсы | `RESOURCE_UPDATE` |
| Отменить обновление ресурсов | `RESOURCE_UPDATE_CANCEL` |

```sh
adb shell am broadcast -n org.asterisk.zcc.ang/features.automation.BroadcastControlReceiver -a org.asterisk.zcc.ang.action.SUBSCRIPTION_UPDATE
```

Локальные записи пропускаются; отмена сохраняет готовые результаты и настройки обновления по расписанию. Ресурсы обновляются с текущими настройками управления ресурсами; отмена также очищает общую очередь ресурсов. Повторные команды одного типа объединяются, пока задача выполняется.

Обновления выполняются в фоне без запуска прокси. Доставка broadcast не означает завершения обновления; результаты доступны в журнале приложения с тегом `BroadcastControl`.

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

При сборке загружается AAR AndroidLibXrayLite фиксированной версии, собираются нативные субмодули и создаются отдельные APK для каждой ABI, а также универсальный APK.

Если Gradle не может найти Android NDK, настройте его через Android Studio, параметр `ndk.dir` в `local.properties` или переменную `ANDROID_NDK_HOME`.

## Лицензия

[GPL-3.0](LICENSE)

## Благодарности и используемые компоненты

- [@XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [@Asterisk4Magisk/AndroidLibXrayLite](https://github.com/Asterisk4Magisk/AndroidLibXrayLite)
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
- [@xchacha20-poly1305/husi](https://github.com/xchacha20-poly1305/husi) — пример реализации функции «Сканировать китайские приложения» для настройки прокси по приложениям
