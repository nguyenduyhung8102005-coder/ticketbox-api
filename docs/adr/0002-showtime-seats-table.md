# ADR 0002: Lưu trạng thái ghế theo từng suất trong bảng `showtime_seats`

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-10-17

## Bối cảnh

Cần biết ghế nào của suất chiếu nào đang trống, đang giữ hay đã bán, và phải đảm bảo
**không bao giờ bán một ghế hai lần**, kể cả khi hàng nghìn request đến cùng lúc.

## Các phương án

1. **Tính động:** ghế đã bán = JOIN `seats` với `booking_items` của các đơn còn hiệu lực.
   Không có một dòng duy nhất để khóa, kiểm tra "ghế trống rồi mới chèn" dễ dính race condition.
2. **Sinh sẵn `showtime_seats`:** mỗi cặp (suất, ghế) là một dòng có cột `status`.

## Quyết định

Chọn phương án 2.

- Giữ ghế là một câu `UPDATE ... WHERE status = 'AVAILABLE'`, nguyên tử, kiểm tra được bằng số dòng bị ảnh hưởng.
- `UNIQUE (showtime_id, seat_id)` là hàng rào cuối cùng ở tầng DB.
- `CHECK` ràng buộc trạng thái nhất quán (ghế `HELD` bắt buộc có `booking_id` và `held_until`).
- Cột `version` sẵn sàng cho optimistic locking.

## Hệ quả

- Tạo suất chiếu phải sinh N dòng (phòng 200 ghế → 200 dòng), cần batch insert.
- Dữ liệu tăng tuyến tính theo số suất; có thể lưu trữ (archive) suất cũ khi cần.
