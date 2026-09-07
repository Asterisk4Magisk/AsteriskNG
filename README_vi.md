[English](README.md) | [简体中文](README_zh_CN.md) | [Русский](README_ru.md) | Tiếng Việt

# AsteriskNG

Ứng dụng khách Xray cho Android, sử dụng [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) và [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Kênh Telegram

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Tính năng

- Các chế độ hoạt động VPN Service, TPROXY(ROOT), TUN2SOCKS(ROOT) và BPF2SOCKS(ROOT)
- VMess, VLESS, Trojan, Shadowsocks, SOCKS, HTTP, Hysteria2, WireGuard, nhóm chiến lược và chuỗi proxy
- Hỗ trợ định dạng đăng ký v2rayNG và Mihomo
- Quản lý cấu hình, proxy, định tuyến, nhật ký và tài nguyên
- Giao diện MIUIX Compose

## Ảnh chụp màn hình

<p align="center">
  <img src="image/screenshot/5.jpg" width="24%" alt="Ảnh chụp màn hình 1" />
  <img src="image/screenshot/6.jpg" width="24%" alt="Ảnh chụp màn hình 2" />
  <img src="image/screenshot/7.jpg" width="24%" alt="Ảnh chụp màn hình 3" />
  <img src="image/screenshot/8.jpg" width="24%" alt="Ảnh chụp màn hình 4" />
</p>

## Chế độ hoạt động

### VPN Service

- Hoạt động không cần quyền root.
- Sử dụng `VpnService` của Android.
- Chạy Xray trong tiến trình ứng dụng thông qua AndroidLibXrayLite.

### TPROXY(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ bằng libsu.
- Sử dụng inbound TPROXY kết hợp với iptables và định tuyến theo chính sách để chuyển tiếp lưu lượng qua proxy trong suốt.

### TUN2SOCKS(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ bằng libsu.
- Sử dụng `hev-socks5-tunnel` để tạo thiết bị TUN có tên cố định `asterisk0`.
- Chuyển lưu lượng đường hầm đến inbound SOCKS5 của Xray cục bộ.

### BPF2SOCKS(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ và chương trình hỗ trợ native `bpf2socks` bằng libsu.
- Sử dụng eBPF mà không tạo thiết bị TUN, chuyển lưu lượng TCP và UDP thu được đến inbound SOCKS5 của Xray cục bộ.
- Cổng cầu nối mặc định là `65532`, cổng inbound SOCKS5 mặc định là `65534`.
- Phải vượt qua kiểm tra khả năng hỗ trợ eBPF trước khi khởi động. Thiết bị không đáp ứng yêu cầu sẽ không thể khởi động chế độ này.

### asteriskd

- Theo dõi địa chỉ IPv4/IPv6 cục bộ và các giao diện chia sẻ kết nối, sau đó cập nhật các quy tắc iptables hoặc bản đồ BPF tương ứng.
- Dọn dẹp các quy tắc mạng thuộc chế độ ROOT đang hoạt động khi dịch vụ dừng.

## Tệp tài nguyên

- Các tệp dùng khi chạy được lưu trong thư mục riêng của ứng dụng `files/xray`.
- Có thể thay thế tệp thực thi Xray đi kèm bằng một tệp thực thi hoặc tệp nén zip chứa `xray`.
- Có thể khôi phục, thay thế bằng tệp cục bộ hoặc cập nhật `geoip.dat`, `geosite.dat` và các tài nguyên khác từ nguồn tích hợp sẵn hoặc nguồn tùy chỉnh.

## Phát triển

Khởi tạo các submodule trước khi biên dịch:

```bash
git submodule update --init --recursive
```

Mở thư mục gốc của dự án trong Android Studio, hoặc biên dịch bằng Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

Trên macOS hoặc Linux:

```bash
./gradlew assembleDebug
```

Quá trình biên dịch chuẩn bị Xray, biên dịch các submodule hỗ trợ native đã cấu hình và đóng gói các ABI được hỗ trợ.

Nếu Gradle không tìm thấy Android NDK, hãy cấu hình qua Android Studio, thuộc tính `ndk.dir` trong `local.properties`, hoặc biến môi trường `ANDROID_NDK_HOME`.

## WSA

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```

## Giấy phép

[GPL-3.0](LICENSE)

## Ghi nhận đóng góp

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
