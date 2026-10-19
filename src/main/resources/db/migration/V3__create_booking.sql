-- =====================================================================
-- V3: Module Booking - trạng thái ghế theo suất, đơn đặt vé
-- =====================================================================

-- TRÁI TIM CỦA HỆ THỐNG: mỗi cặp (suất chiếu, ghế) là đúng một dòng.
-- Được sinh hàng loạt khi tạo suất chiếu. Mọi thao tác giữ/bán ghế
-- đều là UPDATE có điều kiện trên một dòng duy nhất -> dễ khóa, dễ kiểm chứng.
CREATE TABLE showtime_seats (
    id           BIGSERIAL   PRIMARY KEY,
    showtime_id  BIGINT      NOT NULL REFERENCES showtimes (id),
    seat_id      BIGINT      NOT NULL REFERENCES seats (id),
    status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    booking_id   BIGINT,
    held_until   TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,

    -- Hàng rào cuối cùng chống bán trùng ghế, kể cả khi code có bug
    CONSTRAINT uk_showtime_seats UNIQUE (showtime_id, seat_id),
    CONSTRAINT ck_showtime_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED')),
    -- Bất biến dữ liệu: trạng thái và các cột đi kèm phải nhất quán
    CONSTRAINT ck_showtime_seats_state CHECK (
           (status IN ('AVAILABLE', 'BLOCKED') AND booking_id IS NULL     AND held_until IS NULL)
        OR (status = 'HELD'                    AND booking_id IS NOT NULL AND held_until IS NOT NULL)
        OR (status = 'SOLD'                    AND booking_id IS NOT NULL)
    )
);
CREATE INDEX idx_showtime_seats_showtime_status ON showtime_seats (showtime_id, status);
CREATE INDEX idx_showtime_seats_booking         ON showtime_seats (booking_id) WHERE booking_id IS NOT NULL;
-- Partial index cho job quét ghế hết hạn giữ chỗ
CREATE INDEX idx_showtime_seats_held_until      ON showtime_seats (held_until) WHERE status = 'HELD';

CREATE TABLE bookings (
    id               BIGSERIAL     PRIMARY KEY,
    booking_code     VARCHAR(20)   NOT NULL,
    user_id          BIGINT        REFERENCES users (id),       -- NULL nếu khách vãng lai
    customer_email   VARCHAR(255)  NOT NULL,                     -- nơi nhận vé (kể cả khách vãng lai)
    customer_phone   VARCHAR(20),
    showtime_id      BIGINT        NOT NULL REFERENCES showtimes (id),
    status           VARCHAR(20)   NOT NULL,
    total_amount     NUMERIC(12,0) NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    idempotency_key  VARCHAR(64),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_bookings_code            UNIQUE (booking_code),
    CONSTRAINT uk_bookings_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_bookings_status CHECK (status IN (
        'PENDING_PAYMENT', 'CONFIRMED', 'EXPIRED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'
    )),
    CONSTRAINT ck_bookings_amount CHECK (total_amount >= 0)
);
CREATE INDEX idx_bookings_user           ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_pending_expiry ON bookings (expires_at) WHERE status = 'PENDING_PAYMENT';

-- showtime_seats tạo trước bookings nên thêm khóa ngoại sau
ALTER TABLE showtime_seats
    ADD CONSTRAINT fk_showtime_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings (id);

CREATE TABLE booking_items (
    id                BIGSERIAL     PRIMARY KEY,
    booking_id        BIGINT        NOT NULL REFERENCES bookings (id),
    showtime_seat_id  BIGINT        NOT NULL REFERENCES showtime_seats (id),
    price             NUMERIC(12,0) NOT NULL,   -- chụp giá tại thời điểm mua
    ticket_code       VARCHAR(128),             -- nội dung QR, sinh khi đơn CONFIRMED

    CONSTRAINT uk_booking_items_seat        UNIQUE (booking_id, showtime_seat_id),
    CONSTRAINT uk_booking_items_ticket_code UNIQUE (ticket_code),
    CONSTRAINT ck_booking_items_price       CHECK (price >= 0)
);

COMMENT ON TABLE  showtime_seats         IS 'Trạng thái từng ghế trong từng suất chiếu';
COMMENT ON COLUMN showtime_seats.version IS 'Optimistic locking (@Version)';
COMMENT ON COLUMN bookings.idempotency_key IS 'Chống tạo trùng đơn khi client gửi lại request';
COMMENT ON COLUMN booking_items.price    IS 'Giá tại thời điểm mua, không bị ảnh hưởng khi admin đổi giá';
