# ADR 0005: Transactional Outbox → Kafka → consumer idempotent (email + vé QR)

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-10-20

## Bối cảnh

Sau khi xác nhận thanh toán phải (a) cập nhật DB và (b) phát sự kiện để gửi email vé.
Hai hệ thống (PostgreSQL, Kafka) không chung một transaction:

- DB commit, gửi Kafka lỗi → khách mất email.
- Gửi Kafka trước, DB rollback → khách nhận email cho đơn không tồn tại.

## Quyết định

### Outbox

```
Transaction nghiệp vụ ──INSERT──▶ outbox_events (PENDING)          ← cùng commit / cùng rollback
OutboxRelay (mỗi 1s)  ──SELECT ... FOR UPDATE SKIP LOCKED──▶ gửi Kafka, chờ ack (acks=all) ──▶ PUBLISHED
                        gửi lỗi → attempts+1, next_attempt_at lùi 2s, 4s, 8s... ≤ 10 phút; quá 10 lần → FAILED
```

- `OutboxWriter.append` dùng `Propagation.MANDATORY`: gọi ngoài transaction là lỗi lập trình, ném exception ngay.
- `SKIP LOCKED`: chạy nhiều instance không gửi trùng, không chờ nhau.
- Dừng lô ngay ở sự kiện lỗi đầu tiên (Kafka sập thì các sự kiện sau cũng lỗi; không để sự kiện sau vượt trước).
- Key Kafka = `aggregateType:aggregateId` → sự kiện của cùng một aggregate vào cùng partition, giữ thứ tự.
- Producer `max.block.ms=5000`: Kafka sập thì relay lỗi sau 5 giây thay vì giữ transaction 60 giây.
- Dọn sự kiện `PUBLISHED` quá 7 ngày lúc 3 giờ sáng.
- Chọn polling thay vì Debezium (CDC) cho đơn giản; khi tải lớn có thể chuyển sang CDC mà không đổi phía consumer.

### Envelope

`{eventId, eventType, aggregateType, aggregateId, occurredAt, payload}`. Payload mang đủ dữ liệu cho consumer
(event-carried state transfer): module notification không đọc bảng của module booking.

### Consumer idempotent + retry + DLT

Kafka đảm bảo at-least-once, relay cũng có thể gửi lại. Consumer `notification`:

```
BEGIN
  INSERT processed_events(eventId, 'notification') ON CONFLICT DO NOTHING   -- 0 dòng: đã xử lý, bỏ qua
  sinh QR (ZXing) → render Thymeleaf → gửi SMTP          -- lỗi: ROLLBACK, xóa luôn dấu "đã xử lý"
COMMIT
```

- `DefaultErrorHandler`: thử lại 4 lần với backoff 1s, 2s, 4s, 8s rồi chuyển sang `ticketbox.booking-events.DLT`.
  Tin nhắn sai định dạng (`NonRetryableEventException`, `JsonProcessingException`) vào DLT ngay.
- Tin nhắn trong DLT chưa được đánh dấu `processed_events`, đẩy lại sau khi sửa lỗi sẽ được xử lý.
- Khe còn lại: email đã gửi nhưng COMMIT lỗi → gửi trùng lần sau. SMTP không tham gia transaction; gửi trùng
  vẫn tốt hơn không gửi.

### Vé QR

- `ticket_code = TBK1.<base64url(bookingItemId.showtimeId.showtimeSeatId)>.<HMAC-SHA256 16 byte>` (~42 ký tự).
- Máy soát vé xác minh offline bằng HMAC; `POST /api/v1/admin/tickets/verify` kiểm tra thêm trạng thái đơn trong DB.
- QR đính kèm dạng ảnh inline `cid:` (Gmail/Outlook chặn ảnh base64 nhúng trong HTML).
- HMAC đối xứng: máy soát vé phải giữ khóa. Nếu cần máy soát vé chỉ giữ khóa công khai → Ed25519 (mã dài hơn).

### Môi trường

- Dev: `docker compose` có Kafka (KRaft, 1 node) và Mailpit (SMTP giả, xem thư tại http://localhost:8025).
  Chọn Mailpit thay MailHog vì MailHog đã ngừng phát triển; cùng cổng 1025/8025.
- Test: Kafka thật bằng Testcontainers (`apache/kafka-native`), mail sender giả trong bộ nhớ có thể giả lập lỗi SMTP.

## Hệ quả

- Email đến chậm hơn tối đa ~1 giây (chu kỳ relay) so với gửi trực tiếp; đổi lại không bao giờ mất hay sai.
- Thêm Kafka vào hạ tầng dev; Giai đoạn 4 (flash sale) dùng lại cùng hạ tầng.
- Chưa có check-in vé (chặn một mã quét hai lần) và giao diện xem/đẩy lại DLT.
