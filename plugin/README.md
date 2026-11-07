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
