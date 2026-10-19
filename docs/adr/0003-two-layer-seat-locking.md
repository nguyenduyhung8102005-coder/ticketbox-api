# ADR 0003: Giữ ghế bằng hai lớp Redis Lua + UPDATE có điều kiện

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-10-19

## Bối cảnh

1.000 người cùng bấm giữ ghế F7 của một suất hot trong cùng một giây. Yêu cầu:

- Đúng một người giữ được ghế; những người còn lại nhận lỗi rõ ràng **ngay lập tức**.
- Một đơn có nhiều ghế thì giữ **tất cả hoặc không ghế nào**.
- Không làm nghẽn PostgreSQL (connection pool chỉ vài chục kết nối).

## Các phương án

| Chiến lược | Cách làm | Ưu | Nhược |
|---|---|---|---|
| Pessimistic lock | `SELECT ... FOR UPDATE` | Đơn giản, chắc chắn đúng | Request xếp hàng giữ connection, dễ deadlock khi khóa nhiều ghế |
| Optimistic lock | Cột `version`, `@Version` | Không giữ khóa lâu | Tranh chấp cao thì hầu hết thất bại, phải thử lại |
| UPDATE có điều kiện | `UPDATE ... WHERE status = 'AVAILABLE'`, đếm số dòng | Một câu lệnh, nguyên tử, nhanh | Vẫn chạm DB với mọi request |
| Redis lock | Lua script | Cực nhanh, không chạm DB | Redis không phải nguồn sự thật |

## Quyết định

Kết hợp hai lớp:

1. **Redis Lua script** (`SeatHoldCache`) kiểm tra rồi `SET ... PX` cho tất cả key ghế trong một bước nguyên tử.
   Request thua bị từ chối trước khi mở transaction. Key dùng hash tag `seat:hold:{showtimeId}:{id}`
   để chạy được trên Redis Cluster. Giá trị key là mã đơn, nên khi nhả chỉ xóa key do chính mình giữ.
2. **UPDATE có điều kiện** (`ShowtimeSeatRepository.holdSeats`) là nguồn sự thật. Số dòng cập nhật khác số ghế
   yêu cầu thì ném lỗi, transaction rollback, key Redis được nhả.

Các quyết định đi kèm:

- Bước Redis chạy **ngoài** transaction (`TransactionTemplate` chỉ bọc bước DB), để request thua không chiếm connection.
- Redis lỗi thì bỏ qua lớp 1 và chỉ dùng lớp 2: chậm hơn nhưng không sai.
- Lỗi ở bất kỳ đâu sau khi giữ key (kể cả lúc commit) đều nhả key, tránh ghế bị "kẹt" 10 phút.
- Hủy đơn và job hết hạn: cập nhật DB trước, **sau khi commit** mới nhả Redis.
- Job hết hạn dùng `FOR UPDATE SKIP LOCKED` thay vì ShedLock: nhiều instance chia nhau xử lý, không chờ nhau.
- `Idempotency-Key` gắn kèm userId, lưu ở cột `bookings.idempotency_key` có unique constraint.

## Hệ quả

- `BookingConcurrencyIT` chứng minh: 100 luồng tranh một ghế thì đúng 1 thành công; bỏ Redis vẫn đúng;
  bản "không khóa" bán trùng ghế.
- Hai request cùng `Idempotency-Key` gửi **song song** thì request thứ hai có thể nhận 409 (ghế đang do request
  thứ nhất giữ trong Redis); gửi lại sau đó sẽ nhận đúng đơn cũ. Chấp nhận được với client thông thường.
- Ghế quá hạn nhưng job chưa quét (tối đa 30 giây) vẫn hiện `HELD`. Có thể bổ sung lắng nghe sự kiện
  hết hạn key của Redis để phản hồi nhanh hơn, nhưng vẫn giữ job vì sự kiện Redis có thể mất.
