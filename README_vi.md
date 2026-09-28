[English](README.md) | [简体中文](README_zh_CN.md) | [Русский](README_ru.md) | Tiếng Việt

# AsteriskNG

Ứng dụng khách Xray với giao diện đồ họa cho Android.

## Kênh Telegram

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Chế độ hoạt động

### VPN Service

- Hoạt động không cần quyền root.
- Sử dụng `VpnService` của Android.

### TPROXY(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ bằng libsu.
- Sử dụng iptables và định tuyến theo chính sách để xử lý lưu lượng proxy trong suốt.

### TUN2SOCKS(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ bằng libsu.
- Sử dụng `hev-socks5-tunnel` để tạo thiết bị TUN và chuyển lưu lượng đến inbound SOCKS5 của Xray.

### BPF2SOCKS(ROOT)

- Chạy trực tiếp tệp thực thi Xray cục bộ bằng libsu.
- Sử dụng `bpf2socks` để chặn bắt lưu lượng và chuyển đến inbound SOCKS5 của Xray.
- Khả năng sử dụng phụ thuộc vào hỗ trợ eBPF của nhân hệ điều hành trên thiết bị.

### asteriskd

- Theo dõi địa chỉ IPv4/IPv6 cục bộ và các giao diện chia sẻ kết nối, sau đó cập nhật các quy tắc iptables hoặc bản đồ BPF tương ứng.
- Dọn dẹp các quy tắc mạng thuộc chế độ ROOT đang hoạt động khi dịch vụ dừng.

## Tệp tài nguyên

- Các tệp dùng khi chạy ở chế độ ROOT được lưu trong thư mục riêng của ứng dụng `files/xray`.
- Có thể thêm hoặc thay thế tài nguyên tùy chỉnh bằng tệp cục bộ, hoặc cập nhật từ các URL đã cấu hình.

## Điều khiển qua broadcast

Bật **Điều khiển qua broadcast** trong cài đặt, sau đó gửi broadcast chỉ định rõ bộ nhận như bên dưới. Tiền tố Action là `org.asterisk.zcc.ang.action.`.

| Thao tác | Hậu tố Action |
| --- | --- |
| Khởi động proxy | `PROXY_START` |
| Dừng proxy | `PROXY_STOP` |
| Bật/tắt proxy | `PROXY_TOGGLE` |
| Cập nhật tất cả đăng ký URL | `SUBSCRIPTION_UPDATE` |
| Hủy cập nhật đăng ký qua broadcast | `SUBSCRIPTION_UPDATE_CANCEL` |
| Cập nhật tất cả tài nguyên | `RESOURCE_UPDATE` |
| Hủy cập nhật tài nguyên | `RESOURCE_UPDATE_CANCEL` |

```sh
adb shell am broadcast -n org.asterisk.zcc.ang/features.automation.BroadcastControlReceiver -a org.asterisk.zcc.ang.action.SUBSCRIPTION_UPDATE
```

Cập nhật đăng ký bỏ qua mục cục bộ; khi hủy, kết quả đã hoàn thành và cài đặt cập nhật theo lịch được giữ nguyên. Tài nguyên được cập nhật theo cấu hình hiện tại trong Quản lý tài nguyên; thao tác hủy cũng xóa hàng đợi tài nguyên dùng chung. Các lệnh cập nhật cùng loại được gộp khi tác vụ đang chạy.

Cập nhật chạy trong nền mà không khởi động proxy. Broadcast đã được gửi đến không có nghĩa là cập nhật đã hoàn tất; xem kết quả trong nhật ký ứng dụng với thẻ `BroadcastControl`.

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

Quá trình biên dịch tải xuống AAR AndroidLibXrayLite với phiên bản cố định, biên dịch các submodule native và tạo APK riêng cho từng ABI cùng với APK universal.

Nếu Gradle không tìm thấy Android NDK, hãy cấu hình qua Android Studio, thuộc tính `ndk.dir` trong `local.properties`, hoặc biến môi trường `ANDROID_NDK_HOME`.

## Giấy phép

[GPL-3.0](LICENSE)

## Ghi nhận đóng góp

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
- [@xchacha20-poly1305/husi](https://github.com/xchacha20-poly1305/husi) — mã tham khảo cho tính năng “Quét ứng dụng Trung Quốc” trong phần proxy theo ứng dụng
