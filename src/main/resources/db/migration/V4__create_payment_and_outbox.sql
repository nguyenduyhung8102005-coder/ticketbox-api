-- =====================================================================
-- V4: Module Payment + hạ tầng sự kiện (Transactional Outbox)
-- =====================================================================

CREATE TABLE payments (
    id               BIGSERIAL     PRIMARY KEY,
    booking_id       BIGINT        NOT NULL REFERENCES bookings (id),
    provider         VARCHAR(20)   NOT NULL,
    provider_txn_id  VARCHAR(100),
    amount           NUMERIC(12,0) NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    raw_response     JSONB,                       -- lưu nguyên payload để đối soát
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Cổng thanh toán gọi IPN nhiều lần vẫn không ghi nhận tiền hai lần
    CONSTRAINT uk_payments_provider_txn UNIQUE (provider, provider_txn_id),
    CONSTRAINT ck_payments_provider CHECK (provider IN ('VNPAY', 'MOMO', 'ZALOPAY')),
    CONSTRAINT ck_payments_status   CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_amount   CHECK (amount >= 0)
);
CREATE INDEX idx_payments_booking   ON payments (booking_id);
-- Cho job đối soát: tìm giao dịch treo ở INITIATED
CREATE INDEX idx_payments_initiated ON payments (created_at) WHERE status = 'INITIATED';

-- Transactional Outbox: sự kiện được ghi CÙNG transaction với thay đổi nghiệp vụ,
-- sau đó một tiến trình riêng đẩy lên message broker -> không mất sự kiện.
CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

-- Consumer idempotent: ghi nhận sự kiện đã xử lý, nhận lại lần 2 thì bỏ qua
CREATE TABLE processed_events (
    event_id      BIGINT       NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);
