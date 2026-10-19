# Ticketbox API

Backend hệ thống đặt vé xem phim và sự kiện trực tuyến, tập trung vào các bài toán xử lý đồng thời cao:
chống bán trùng ghế, thanh toán tin cậy và chịu tải khi mở bán vé hot.

> Trạng thái: **Giai đoạn 2 – Giữ ghế và chống tranh chấp** ✅ (Giai đoạn 0, 1 đã xong)

## Công nghệ

| Hạng mục | Công nghệ |
|---|---|
| Ngôn ngữ / Framework | Java 21, Spring Boot 3.5 |
| Bảo mật | Spring Security 6, JWT (jjwt), BCrypt, refresh token + blacklist trên Redis |
| Database | PostgreSQL 16, Spring Data JPA, Flyway |
| Cache / Lock | Redis 7 (Lua script giữ ghế all-or-nothing) |
| Message Queue | Kafka (Giai đoạn 3–4) |
| Tài liệu API | springdoc-openapi (Swagger UI) |
| Test | JUnit 5, MockMvc, Testcontainers, JaCoCo |
| Hạ tầng | Docker, Docker Compose, GitHub Actions |

## Kiến trúc

Modular Monolith: một ứng dụng Spring Boot, chia module theo nghiệp vụ. Lý do xem tại
[ADR 0001](docs/adr/0001-modular-monolith.md).

```
src/main/java/com/ticketbox
├── auth/           Đăng ký, đăng nhập, JWT, refresh, logout    (Giai đoạn 1)
├── user/           Tài khoản, tạo admin lúc khởi động          (Giai đoạn 1)
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
[ADR 0002](docs/adr/0002-showtime-seats-table.md) về thiết kế bảng `showtime_seats`,
[ADR 0003](docs/adr/0003-two-layer-seat-locking.md) về cơ chế giữ ghế hai lớp.

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
| http://localhost:8081/actuator/health | Trạng thái ứng dụng, DB, Redis |
| http://localhost:8081/swagger-ui.html | Tài liệu API (bấm **Authorize**, dán access token) |

Profile `dev` tự tạo tài khoản admin khi khởi động: `admin@ticketbox.local` / `Admin@123456`
(đổi bằng biến `ADMIN_EMAIL`, `ADMIN_PASSWORD`).

## API

| API | Method | Quyền | Mô tả |
|---|---|---|---|
| `/api/v1/auth/register`, `/login`, `/refresh` | POST | Public | Đăng ký, đăng nhập, đổi refresh token |
| `/api/v1/auth/logout` | POST | Đăng nhập | Thu hồi access token (blacklist Redis) và refresh token |
| `/api/v1/me` | GET | Đăng nhập | Thông tin tài khoản |
| `/api/v1/shows?type=MOVIE&status=PUBLISHED&q=&page=0` | GET | Public | Danh sách phim/sự kiện, lọc bằng Specification |
| `/api/v1/shows/{slug}` | GET | Public | Chi tiết |
| `/api/v1/shows/{id}/showtimes?date=2026-10-20&city=Hà Nội` | GET | Public | Lịch chiếu theo ngày (giờ VN) và thành phố |
| `/api/v1/admin/shows` | GET/POST/PUT/DELETE | ADMIN | Quản lý phim/sự kiện |
| `/api/v1/admin/venues`, `/admin/auditoriums` | GET/POST | ADMIN | Địa điểm, phòng chiếu |
| `/api/v1/admin/auditoriums/{id}/seats` | GET/POST | ADMIN | Tạo sơ đồ ghế hàng loạt (JDBC batch) |
| `/api/v1/admin/showtimes` | POST | ADMIN | Tạo suất + bảng giá + sinh `showtime_seats` (INSERT … SELECT, 1 transaction) |
| `/api/v1/admin/showtimes/{id}/status` | PATCH | ADMIN | Mở bán, tạm dừng, hủy, kết thúc |
| `/api/v1/showtimes/{id}/seats` | GET | Public | Sơ đồ ghế kèm trạng thái và giá |
| `/api/v1/bookings` (header `Idempotency-Key`) | POST | Đăng nhập | Giữ ghế, tạo đơn chờ thanh toán 10 phút |
| `/api/v1/bookings/{code}` | GET / DELETE | Chủ đơn | Xem đơn / hủy đơn và nhả ghế |
| `/api/v1/me/bookings` | GET | Đăng nhập | Lịch sử đặt vé |

## Chống bán trùng ghế

```
POST /bookings [F7, F8]
   │
   ▼
① Redis Lua: giữ TẤT CẢ ghế hoặc KHÔNG ghế nào (TTL 10 phút)
   │   thất bại → 409 ngay, chưa mở transaction, chưa chiếm connection DB
   ▼
② PostgreSQL, 1 transaction: INSERT booking + UPDATE showtime_seats ... WHERE status = 'AVAILABLE'
   │   số dòng cập nhật ≠ số ghế → rollback + nhả key Redis (so token) → 409
   ▼
③ 201 Created, đơn PENDING_PAYMENT, hết hạn sau 10 phút
```

- Redis là lớp chặn nhanh, **PostgreSQL là nguồn sự thật**: Redis lỗi thì hệ thống tự chuyển sang chỉ dùng DB (chậm hơn, vẫn đúng).
- Job mỗi 30 giây trả ghế của đơn quá hạn, dùng `FOR UPDATE SKIP LOCKED` nên chạy nhiều instance không xử lý trùng.
- `Idempotency-Key`: client gửi lại request (mạng chập chờn, bấm 2 lần) nhận lại đúng đơn cũ.

Chi tiết và so sánh 4 chiến lược khóa: [ADR 0003](docs/adr/0003-two-layer-seat-locking.md).

## Kiểm thử

```bash
mvn test       # unit test (nhanh, không cần Docker)
mvn verify     # unit + integration test với PostgreSQL/Redis thật (cần Docker) + báo cáo coverage
```

Báo cáo coverage: `target/site/jacoco/index.html`.

Test đồng thời (`BookingConcurrencyIT`, PostgreSQL + Redis thật):

| Kịch bản | Kết quả |
|---|---|
| 100 luồng cùng giữ ghế F7 | Đúng 1 thành công, 99 nhận `SEAT_ALREADY_TAKEN`, không có đơn rác |
| Bỏ Redis, chỉ còn `UPDATE` có điều kiện, 50 luồng | Đúng 1 dòng được cập nhật |
| **Không khóa** (SELECT rồi mới UPDATE), 20 luồng | Nhiều người cùng "giữ được" một ghế → minh họa lỗi bán trùng |
| Giữ [F7, F8] khi F8 đã bị giữ | F7 vẫn trống (all-or-nothing) |
| Redis mất key giữa chừng | PostgreSQL vẫn chặn, key rác được nhả |
| 50 luồng tranh các cặp ghế chồng lấn | Không ghế nào thuộc 2 đơn |

Integration test còn kiểm chứng các ràng buộc quan trọng ở tầng database:

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
- [x] **Giai đoạn 1:** JWT, CRUD phim/sự kiện, rạp, sơ đồ ghế, suất chiếu
- [x] **Giai đoạn 2:** Giữ ghế, chống tranh chấp (Redis Lua + PostgreSQL), test đồng thời
- [ ] **Giai đoạn 3:** Thanh toán VNPay, Outbox pattern, email xác nhận + vé QR
- [ ] **Giai đoạn 4:** Cache, flash sale với Kafka, rate limiting, load test k6
