# ADR 0004: Thanh toán VNPay - IPN là nguồn sự thật, đối soát và hoàn tiền tự động

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-10-20

## Bối cảnh

Khách giữ ghế 10 phút rồi thanh toán qua VNPay. Các rủi ro cần xử lý:

- Return URL đi qua trình duyệt: khách sửa được tham số, hoặc đóng tab trước khi tới.
- VNPay gọi IPN nhiều lần (tối đa 10 lần, cách 5 phút) nếu không nhận phản hồi `00`/`02`, có thể song song.
- IPN có thể mất hẳn (server sập đúng lúc VNPay gọi).
- Khách trả tiền đúng lúc giữ chỗ hết hạn; ghế có thể đã được người khác mua.
- Khách mở 2 tab và trả tiền 2 lần cho cùng một đơn.

## Quyết định

### 1. Chỉ IPN (và job đối soát) được đổi trạng thái

```
POST /payments ─▶ payment INITIATED, ký HMAC-SHA512 → paymentUrl (vnp_ExpireDate = hạn giữ ghế)
IPN (server-to-server) ─▶ chữ ký (97) → giao dịch tồn tại (01) → số tiền (04) → đã xử lý? (02) → cập nhật (00)
Return URL ─▶ chỉ xác minh chữ ký và HIỂN THỊ trạng thái đang có trong DB
```

- Mỗi lần bấm "Thanh toán" là một `vnp_TxnRef` mới (`mã đơn + 6 ký tự`), tối đa 5 lần/đơn.
- `payments.created_at` chính là `vnp_CreateDate`; giá trị này được gửi lại làm `vnp_TransactionDate`
  khi truy vấn/hoàn tiền. Thời gian auditing được cắt về micro giây để giá trị trong bộ nhớ trùng với DB.
- Logic ký được kiểm chứng giống hệt code mẫu Java chính thức của VNPay (`VnPayGatewayTest`).

### 2. Idempotent bằng khóa dòng + trạng thái, không chỉ bằng "đã tồn tại"

Bản phác thảo ban đầu dùng `existsByProviderAndProviderTxnId` để bỏ qua IPN lặp lại. Cách này sai với thiết kế
hiện tại: dòng `payments` được tạo NGAY khi sinh link (INITIATED), nên mọi IPN, kể cả lần đầu, đều thấy "đã tồn tại".
Thay vào đó:

1. `SELECT ... FOR UPDATE` dòng payment theo `vnp_TxnRef` → các IPN song song xếp hàng tại đây.
2. Không còn `INITIATED` → trả `02` (VNPay ngừng gọi lại), không xử lý lần 2.

`PaymentFlowIT.ipnConcurrent_20Calls_processedExactlyOnce`: 20 IPN cùng lúc → đúng 1 lần `00`, 19 lần `02`,
1 sự kiện outbox.

### 3. Một transaction cho toàn bộ hệ quả

`PaymentProcessor` (dùng chung cho IPN và đối soát) chạy trong transaction đã khóa dòng payment:
payment → booking `CONFIRMED` → ghế `SOLD` → sinh `ticket_code` có chữ ký → ghi `outbox_events`.
Thứ tự khóa luôn là payment → booking → ghế (theo id tăng dần) để hạn chế deadlock.

### 4. Ca khó "đã thu tiền nhưng hết giữ chỗ"

`BookingPaymentService.confirmPaid` khóa đơn, rồi khóa các ghế của đơn:

| Trạng thái đơn | Ghế | Kết quả |
|---|---|---|
| `PENDING_PAYMENT` | đang HELD bởi đơn | `CONFIRMED` |
| `EXPIRED` / `CANCELLED` | tất cả còn `AVAILABLE` | giành lại ghế, `CONFIRMED` (`CONFIRMED_LATE`) |
| `EXPIRED` / `CANCELLED` | có ghế đã thuộc người khác | đơn + payment `REFUND_PENDING` (`SEATS_UNAVAILABLE`) |
| `CONFIRMED` (bởi giao dịch khác) | - | payment `REFUND_PENDING` (`DUPLICATE_PAYMENT`) |

Hàng rào cuối ở DB: unique index một phần `uk_payments_booking_success` (tối đa 1 giao dịch SUCCESS mỗi đơn).

### 5. Hoàn tiền bất đồng bộ

Ghi `PAYMENT_REFUND_REQUESTED` vào outbox thay vì gọi API hoàn tiền trong transaction IPN (không gọi HTTP khi
đang giữ khóa). Consumer `payment-refund` gọi API `refund`; lỗi thì retry + DLT. Mã `94` (đã có yêu cầu đang xử lý)
được coi là thành công. Khách nhận 2 email: xin lỗi/đang hoàn tiền, và đã hoàn tiền.

### 6. Đối soát mỗi 5 phút (lưới an toàn)

- `INITIATED` quá 15 phút → `querydr`: đã trả → xử lý như IPN; lỗi/đảo → `FAILED`;
  không tìm thấy/chưa hoàn tất mà quá 30 phút → `FAILED` (khách bỏ dở).
- `REFUND_PENDING` quá 10 phút → `querydr` trước (05/06 = VNPay đang/đã hoàn → chốt `REFUNDED`, tránh hoàn 2 lần),
  chưa thì gọi `refund` lại; tối đa 5 lần rồi chờ xử lý tay.
- Gọi API ngoài transaction, chỉ khóa dòng khi ghi kết quả và kiểm tra lại trạng thái (IPN có thể vừa tới).
- Phản hồi API sai chữ ký bị coi là lỗi.

### 7. Lưu nguyên văn mọi trao đổi

`payment_logs` lưu mọi IPN / Return / truy vấn / hoàn tiền, kể cả request chữ ký sai, trong transaction riêng
(`REQUIRES_NEW`) để không mất khi nghiệp vụ rollback. `payments.raw_response` giữ tham số đã dùng để chốt trạng thái.

## Hệ quả

- Khi chạy local, IPN chỉ tới được nếu ứng dụng có URL công khai (ngrok/cloudflared) và URL đó được khai báo
  trong trang quản trị merchant sandbox. Không có IPN thì job đối soát vẫn chốt được giao dịch sau 15 phút.
- Chưa hỗ trợ hoàn tiền theo yêu cầu khách (đơn `CONFIRMED` → hoàn) và hoàn một phần.
- Hai instance cùng đối soát một giao dịch chỉ tốn thêm lượt truy vấn; ghi kết quả vẫn đúng nhờ khóa dòng.
