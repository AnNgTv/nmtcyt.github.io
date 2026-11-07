# NightmareLink 1.1.0

Đồng bộ dữ liệu Minecraft lên website NIGHTMARE TCYT.

## Nguồn dữ liệu chính
- Rank: LuckPerms primary group.
- Xu: PlayerPoints API theo UUID.
- Tiền: Vault Economy provider (EssentialsX Economy).
- Tài khoản: tên người chơi Minecraft trùng tên AuthMe để backend ghép hồ sơ website.

Plugin gửi snapshot khi người chơi vào server, theo chu kỳ `sync-seconds` (mặc định 60 giây), và theo lệnh `/nmtcyt sync`.

## Phụ thuộc
- Paper 1.20.6+
- AuthMe Reloaded
- LuckPerms
- PlayerPoints
- Vault
- EssentialsX (cung cấp Economy cho Vault)

## Cấu hình bảo mật
1. Tạo một chuỗi bí mật riêng bằng `openssl rand -hex 32`.
2. Đặt chuỗi đó làm biến môi trường `PLUGIN_SHARED_KEY` trong Render backend.
3. Điền đúng cùng chuỗi vào `plugins/NightmareLink/config.yml` tại `plugin-key`.
4. Không commit hoặc gửi chuỗi bí mật lên GitHub/Discord.
5. Khởi động lại backend và Minecraft server.

`api-base-url` mặc định trỏ đến backend Render. Chỉ dùng HTTPS.

## API
Plugin gửi `POST /internal/sync/player` với UUID, username, rank, points và balance. Backend xác thực bằng `X-Plugin-Key` và lưu snapshot trong MySQL table `nightmare_player_sync`.

Website dùng `/api/auth/me`, `/api/player/:username` và `/api/top-players` để đọc snapshot. Snapshot cuối cùng vẫn được giữ khi người chơi offline; thời gian cập nhật được trả về trong `syncedAt`.

## NightmareLink 1.2.0 — Website → Minecraft credits

Trang `/profile/` cho phép tài khoản đã đăng nhập tạo yêu cầu cộng `points` hoặc `money` (tối đa 1.000.000 mỗi yêu cầu). Yêu cầu chỉ ở trạng thái `pending` và KHÔNG tự cộng tài nguyên.

Quản trị viên phải xác minh giao dịch/ủy quyền độc lập rồi mới duyệt qua backend:

```sh
curl -X POST 'https://minecraftrechargebackend.onrender.com/internal/commands/REQUEST_UUID/approve' \\
  -H 'X-Admin-Key: YOUR_ADMIN_SHARED_KEY'
```

Cấu hình `ADMIN_SHARED_KEY` trên Render bằng một chuỗi ngẫu nhiên riêng (không dùng lại `PLUGIN_SHARED_KEY`). Không đưa khóa vào website, GitHub, Discord hoặc gửi trong chat. Plugin dùng `PLUGIN_SHARED_KEY` để nhận yêu cầu đã duyệt, áp dụng PlayerPoints/Vault trên server thread, rồi xác nhận trạng thái.

Trạng thái `processing` không được tự động chạy lại sau khi server/backend gặp sự cố giữa lúc cộng và lúc xác nhận. Quản trị viên cần kiểm tra số dư trong game trước khi xử lý thủ công để tránh cộng trùng.
