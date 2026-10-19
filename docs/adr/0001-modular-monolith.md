# ADR 0001: Chọn kiến trúc Modular Monolith

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-10-17

## Bối cảnh

Dự án do một người phát triển, mục tiêu là giải quyết tốt các bài toán khó của hệ thống bán vé
(tranh chấp ghế, thanh toán tin cậy, chịu tải flash sale) trong khoảng 10–12 tuần.

## Các phương án

| Phương án | Ưu | Nhược |
|---|---|---|
| Microservices | Scale độc lập từng phần, triển khai độc lập | Tốn nhiều công cho hạ tầng (service discovery, gateway, distributed tracing, transaction phân tán); khó debug; quá sức với 1 người |
| Monolith không phân lớp rõ | Làm nhanh nhất | Code dính chùm, khó tách ra sau này |
| **Modular Monolith** | Một lần deploy, transaction cục bộ đơn giản; ranh giới module rõ ràng, sẵn sàng tách | Phải tự giữ kỷ luật ranh giới giữa các module |

## Quyết định

Dùng **Modular Monolith**: một ứng dụng Spring Boot, chia package theo nghiệp vụ
(`auth`, `user`, `catalog`, `booking`, `payment`, `notification`, `common`).

Quy tắc:
1. Module chỉ gọi module khác qua service interface công khai hoặc domain event.
2. Không truy cập trực tiếp repository/entity của module khác.
3. Ứng dụng stateless (JWT, không session) để chạy nhiều instance phía sau load balancer.

## Hệ quả

- Tập trung thời gian vào nghiệp vụ cốt lõi thay vì hạ tầng.
- Khi cần, module `booking` (tải cao nhất) là ứng viên đầu tiên để tách thành service riêng.
