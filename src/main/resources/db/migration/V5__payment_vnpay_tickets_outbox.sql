-- =====================================================================
-- V5: Giai đoạn 3 - Thanh toán VNPay, hoàn tiền, nhật ký cổng thanh toán, outbox có backoff
-- =====================================================================

-- ----- payments: thêm thông tin từ VNPay và trạng thái hoàn tiền -----
-- provider_txn_id = vnp_TxnRef do HỆ THỐNG sinh cho mỗi lần thanh toán (mỗi lần bấm "Thanh toán" là một mã mới),
-- provider_transaction_no = vnp_TransactionNo do VNPay cấp, bắt buộc khi gọi API hoàn tiền.
ALTER TABLE payments
    ALTER COLUMN provider_txn_id SET NOT NULL,
    ADD COLUMN provider_transaction_no VARCHAR(50),
    ADD COLUMN bank_code               VARCHAR(20),
    ADD COLUMN response_code           VARCHAR(10),
    ADD COLUMN paid_at                 TIMESTAMPTZ,
    ADD COLUMN refund_reason           VARCHAR(30),
    ADD COLUMN refund_attempts         INT NOT NULL DEFAULT 0,
    ADD COLUMN refunded_at             TIMESTAMPTZ;

-- REFUND_PENDING: đã thu tiền nhưng không giao được vé (ghế đã bị người khác mua, hoặc khách trả tiền 2 lần)
ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REFUND_PENDING', 'REFUNDED'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_refund_reason
    CHECK (refund_reason IS NULL OR refund_reason IN ('SEATS_UNAVAILABLE', 'DUPLICATE_PAYMENT', 'BOOKING_CLOSED'));

-- Hàng rào cuối: một đơn chỉ có TỐI ĐA MỘT giao dịch được ghi nhận thành công.
-- Khách trả tiền 2 lần (2 tab) thì giao dịch thứ hai phải chuyển sang REFUND_PENDING, không thể cùng là SUCCESS.
CREATE UNIQUE INDEX uk_payments_booking_success ON payments (booking_id) WHERE status = 'SUCCESS';
-- Cho job đối soát: giao dịch đã thu tiền nhưng hoàn tiền chưa xong
CREATE INDEX idx_payments_refund_pending ON payments (updated_at) WHERE status = 'REFUND_PENDING';

-- ----- payment_logs: lưu NGUYÊN VĂN mọi lần cổng thanh toán gọi vào / mình gọi ra -----
-- Kể cả request chữ ký sai hay mã đơn không tồn tại, để đối soát và giải quyết tranh chấp.
CREATE TABLE payment_logs (
    id          BIGSERIAL    PRIMARY KEY,
    payment_id  BIGINT       REFERENCES payments (id),
    provider    VARCHAR(20)  NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    txn_ref     VARCHAR(100),
    payload     JSONB        NOT NULL,
    result      VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_payment_logs_kind CHECK (kind IN ('IPN', 'RETURN', 'QUERY', 'REFUND'))
);
CREATE INDEX idx_payment_logs_txn_ref ON payment_logs (txn_ref, created_at);

-- ----- bookings: thời điểm xác nhận -----
ALTER TABLE bookings ADD COLUMN confirmed_at TIMESTAMPTZ;

-- ----- outbox_events: thử lại có backoff -----
-- Gửi lên Kafka lỗi thì lùi next_attempt_at theo cấp số nhân; quá số lần cho phép thì FAILED (chờ xử lý tay).
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
DROP INDEX idx_outbox_pending;
CREATE INDEX idx_outbox_pending   ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE status = 'PUBLISHED';

COMMENT ON COLUMN payments.provider_txn_id         IS 'vnp_TxnRef: mã giao dịch phía merchant, duy nhất cho mỗi lần thanh toán';
COMMENT ON COLUMN payments.provider_transaction_no IS 'vnp_TransactionNo: mã giao dịch phía VNPay, dùng để hoàn tiền';
COMMENT ON TABLE  payment_logs                     IS 'Nhật ký nguyên văn IPN / Return / truy vấn / hoàn tiền với cổng thanh toán';
COMMENT ON COLUMN outbox_events.next_attempt_at    IS 'Thời điểm sớm nhất được thử gửi lại (backoff)';
