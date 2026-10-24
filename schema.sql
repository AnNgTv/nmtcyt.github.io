CREATE TABLE IF NOT EXISTS nightmare_link_codes (
 id BIGSERIAL PRIMARY KEY, code_hash CHAR(64) NOT NULL UNIQUE,
 minecraft_uuid VARCHAR(36) NOT NULL, minecraft_username VARCHAR(16) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS nightmare_orders (
 id UUID PRIMARY KEY, payment_code VARCHAR(32) NOT NULL UNIQUE,
 minecraft_uuid VARCHAR(36) NOT NULL, minecraft_username VARCHAR(16) NOT NULL,
 amount_vnd BIGINT NOT NULL CHECK(amount_vnd > 0), points INTEGER NOT NULL CHECK(points > 0),
 status VARCHAR(24) NOT NULL DEFAULT 'pending'
 CHECK(status IN ('pending','paid','fulfilled','needs_review','cancelled')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), paid_at TIMESTAMPTZ, fulfilled_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS nightmare_payment_events (
 transaction_id VARCHAR(100) PRIMARY KEY, order_id UUID REFERENCES nightmare_orders(id),
 status VARCHAR(32) NOT NULL, amount_vnd BIGINT, account_number VARCHAR(100),
 payload JSONB NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS nightmare_point_queue (
 order_id UUID PRIMARY KEY REFERENCES nightmare_orders(id), minecraft_uuid VARCHAR(36) NOT NULL,
 points INTEGER NOT NULL CHECK(points > 0),
 status VARCHAR(24) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','done','review')),
 attempts INTEGER NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ, last_error TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_nightmare_queue ON nightmare_point_queue(status,locked_until,created_at);
