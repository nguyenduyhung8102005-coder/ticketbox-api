# Ticketbox API

Backend hệ thống đặt vé xem phim và sự kiện trực tuyến, tập trung vào các bài toán xử lý đồng thời cao:
chống bán trùng ghế, thanh toán tin cậy và chịu tải khi mở bán vé hot.

> Trạng thái: **Giai đoạn 0 – Nền tảng dự án** ✅

## Công nghệ

| Hạng mục | Công nghệ |
|---|---|
| Ngôn ngữ / Framework | Java 21, Spring Boot 3.5 |
| Bảo mật | Spring Security 6 (JWT ở Giai đoạn 1) |
| Database | PostgreSQL 16, Spring Data JPA, Flyway |
| Cache / Lock | Redis 7 |
| Message Queue | Kafka (Giai đoạn 3–4) |
| Tài liệu API | springdoc-openapi (Swagger UI) |
| Test | JUnit 5, MockMvc, Testcontainers, JaCoCo |
| Hạ tầng | Docker, Docker Compose, GitHub Actions |

## Kiến trúc

Modular Monolith: một ứng dụng Spring Boot, chia module theo nghiệp vụ. Lý do xem tại
[ADR 0001](docs/adr/0001-modular-monolith.md).

```
src/main/java/com/ticketbox
├── auth/           Đăng ký, đăng nhập, JWT                     (Giai đoạn 1)
├── user/           Hồ sơ người dùng                            (Giai đoạn 1)
├── catalog/        Phim/sự kiện, rạp, phòng, ghế, suất chiếu    (Giai đoạn 1)
├── booking/        Giữ ghế, chống tranh chấp, đơn đặt vé        (Giai đoạn 2)
├── payment/        Cổng thanh toán, IPN, đối soát              (Giai đoạn 3)
├── notification/   Email, vé QR                                (Giai đoạn 3)
└── common/
    ├── api/        ApiResponse, ApiError, PageResponse
    ├── config/     Security, OpenAPI, JPA Auditing, Clock
    ├── exception/  ErrorCode, BusinessException, GlobalExceptionHandler
    ├── persistence/BaseEntity
    └── web/        RequestIdFilter (truy vết log theo request)
```

Cơ sở dữ liệu: xem [ERD](docs/erd.dbml) (dán vào [dbdiagram.io](https://dbdiagram.io)) và
[ADR 0002](docs/adr/0002-showtime-seats-table.md) về thiết kế bảng `showtime_seats`.

## Chạy dự án

### Yêu cầu

- JDK 21
- Maven 3.9+
- Docker Desktop (hoặc Docker Engine + Compose)

### Lần đầu tiên

```bash
# 1. Tạo file biến môi trường
cp .env.example .env

# 2. (Khuyến nghị) Sinh Maven Wrapper để ai clone về cũng chạy được ./mvnw
mvn -N wrapper:wrapper
```

### Cách 1: Docker Compose cho hạ tầng, chạy app từ IDE/Maven

```bash
docker compose up -d          # khởi động PostgreSQL + Redis
mvn spring-boot:run           # profile mặc định: dev
```

### Cách 2: Không cần docker compose (Testcontainers tự dựng DB)

```bash
mvn spring-boot:test-run      # hoặc chạy TestTicketboxApplication trong IDE
```

### Cách 3: Chạy toàn bộ trong Docker

```bash
docker compose --profile app up -d --build
```

### Kiểm tra

| URL | Mô tả |
|---|---|
| http://localhost:8080/actuator/health | Trạng thái ứng dụng, DB, Redis |
| http://localhost:8080/swagger-ui.html | Tài liệu API |

## Kiểm thử

```bash
mvn test       # unit test (nhanh, không cần Docker)
mvn verify     # unit + integration test với PostgreSQL/Redis thật (cần Docker) + báo cáo coverage
```

Báo cáo coverage: `target/site/jacoco/index.html`.

Integration test hiện kiểm chứng các ràng buộc quan trọng ở tầng database:

- Không thể tạo 2 suất chiếu trùng giờ trong cùng một phòng (exclusion constraint).
- Không thể có 2 bản ghi cho cùng một ghế trong cùng một suất (unique constraint).
- Ghế ở trạng thái `HELD` bắt buộc gắn với đơn và có hạn giữ chỗ (check constraint).
- Email người dùng là duy nhất, không phân biệt hoa thường.

## Quy ước

### Định dạng response

```json
// Thành công
{ "success": true, "data": { }, "timestamp": "2026-10-17T14:00:00Z" }

// Thất bại
{
  "success": false,
  "error": {
    "code": "SEAT_ALREADY_TAKEN",
    "message": "Ghế đã có người chọn, vui lòng chọn ghế khác",
    "requestId": "3f1c9a2e-..."
  },
  "timestamp": "2026-10-17T14:00:00Z"
}
```

Mọi response đều có header `X-Request-Id` khớp với log phía server.

### Profile cấu hình

| Profile | Dùng khi | Ghi chú |
|---|---|---|
| `dev` | Phát triển local | Mặc định; có giá trị mặc định khớp `.env.example` |
| `test` | Integration test | Kết nối do Testcontainers cấp |
| `prod` | Triển khai thật | Mọi bí mật bắt buộc truyền qua biến môi trường; tắt Swagger |

### Database migration

- Mọi thay đổi schema đi qua file Flyway mới: `V{n}__mo_ta.sql`.
- **Không sửa** file migration đã chạy; muốn thay đổi thì tạo file mới.
- Hibernate chỉ `validate`, không tự tạo bảng.

### Git

- Nhánh: `main` (ổn định), `develop`, `feature/<tên>`.
- Commit theo [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`.

## Lộ trình

- [x] **Giai đoạn 0:** Nền tảng dự án, schema, xử lý lỗi chung, CI
- [ ] **Giai đoạn 1:** JWT, CRUD phim/sự kiện, rạp, sơ đồ ghế, suất chiếu
- [ ] **Giai đoạn 2:** Giữ ghế, chống tranh chấp (Redis Lua + PostgreSQL), test đồng thời
- [ ] **Giai đoạn 3:** Thanh toán VNPay, Outbox pattern, email xác nhận + vé QR
- [ ] **Giai đoạn 4:** Cache, flash sale với Kafka, rate limiting, load test k6
