# NIGHTMARE TCYT — tích hợp SePay/AuthMe/PlayerPoints

Bộ mã khởi đầu để ghép vào backend Express đang chạy trên Render. Không thay thế index.js hiện có.

## Cần có
- PostgreSQL bền vững; không dùng JSON/local filesystem để lưu giao dịch.
- Node package: `pg`, `jsonwebtoken`.
- Render env: `DATABASE_URL`, `JWT_SECRET`, `PLUGIN_SHARED_SECRET`, `SEPAY_WEBHOOK_SECRET`, `SEPAY_ACCOUNT_NUMBER`, `POINTS_PER_1000_VND`, `WEB_ORIGIN`, `BANK_NAME`, `BANK_OWNER`, `BANK_ACCOUNT`, và tùy chọn `PGSSLMODE=require`.
- Tạo cùng `PLUGIN_SHARED_SECRET` trong `plugins/NightmareLink/config.yml`.
- Không bao giờ đưa secrets vào GitHub hoặc HTML/JS phía trình duyệt.

## Gắn vào Express
Tạo PostgreSQL Pool như ví dụ bên dưới, chạy `schema.sql`, rồi đăng ký routes từ `sepay-addon.js` trong app hiện tại. Không tạo app/listen thứ hai.

```js
const { Pool } = require('pg');
const { registerNightmarePayments } = require('./sepay-addon');
const pgPool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.PGSSLMODE === 'require' ? { rejectUnauthorized: false } : undefined
});

// Sửa middleware express.json hiện tại để lưu raw body cho webhook:
app.use(express.json({
  limit: '256kb',
  verify(req, res, buf) {
    if (req.originalUrl.split('?')[0] === '/webhooks/sepay') {
      req.rawBody = Buffer.from(buf);
    }
  }
}));

registerNightmarePayments(app, pgPool);
```

Nếu app hiện đã có `express.json()`, sửa dòng đó, đừng thêm một parser sau khi body đã được parse. Giữ nguyên `app.listen` cũ.

## SePay
Tạo webhook HTTPS `https://minecraftrechargebackend.onrender.com/webhooks/sepay`, loại giao dịch tiền vào, xác thực HMAC-SHA256. Secret trong Render env. Mã nội dung chuyển khoản được tạo bởi API, có dạng `NTCYT` + 12 ký tự hex.

## Liên kết AuthMe
Plugin kiểm tra `AuthMeApi.isAuthenticated(player)` tại máy chủ, rồi gửi UUID + tên người chơi lên `/internal/link/create`. Người chơi nhập mã một lần trên website qua `/api/minecraft/link/claim`. Không đọc `authme.db` từ Render, vì file SQLite ở máy chủ Minecraft.

## Cấp điểm
Backend tạo queue khi giao dịch đã được xác thực. Plugin lấy queue qua `/internal/points/claim` và ACK bằng `/internal/points/:id/ack`. Plugin dùng ledger cục bộ và fail-closed: nếu crash ở thời điểm không rõ giao dịch đã cộng điểm chưa, đưa đơn sang `review`, không tự cấp lại. Cần đối soát thủ công để tránh cộng trùng.

## Kiểm thử
Không có endpoint giả lập thanh toán thành công. Webhook test chỉ kiểm tra endpoint; chỉ đơn khớp tài khoản nhận, mã đơn, số tiền và giao dịch thật mới tạo queue.
