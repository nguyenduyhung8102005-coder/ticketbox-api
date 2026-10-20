package com.ticketbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.dto.ShowDtos.ShowRequest;
import com.ticketbox.catalog.dto.ShowtimeDtos.CreateShowtimeRequest;
import com.ticketbox.catalog.dto.VenueDtos.AuditoriumRequest;
import com.ticketbox.catalog.dto.VenueDtos.RowSpec;
import com.ticketbox.catalog.dto.VenueDtos.SeatLayoutRequest;
import com.ticketbox.catalog.dto.VenueDtos.VenueRequest;
import com.ticketbox.catalog.service.ShowService;
import com.ticketbox.catalog.service.ShowtimeService;
import com.ticketbox.catalog.service.VenueService;
import com.ticketbox.common.outbox.OutboxRelay;
import com.ticketbox.payment.service.PaymentProperties;
import com.ticketbox.payment.vnpay.VnPayApiClient;
import com.ticketbox.payment.vnpay.VnPaySigner;
import com.ticketbox.support.CapturingMailSender;
import com.ticketbox.support.TestMailConfiguration;
import com.ticketbox.user.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lớp cha cho MỌI integration test: PostgreSQL + Redis + Kafka thật (Testcontainers), ứng dụng chạy trên cổng
 * ngẫu nhiên. Mọi lớp test dùng chung đúng một cấu hình (kể cả @MockitoBean) nên Spring tái sử dụng MỘT context
 * và một bộ container cho cả bộ test, thay vì khởi động lại cho từng lớp.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "MatKhau@123";

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected VenueService venueService;
    @Autowired
    protected ShowService showService;
    @Autowired
    protected ShowtimeService showtimeService;
    @Autowired
    protected OutboxRelay outboxRelay;
    @Autowired
    protected CapturingMailSender mailSender;
    @Autowired
    protected PaymentProperties paymentProperties;

    /** API truy vấn/hoàn tiền của VNPay: không gọi ra Internet trong test. Mock tự reset sau mỗi test. */
    @MockitoBean
    protected VnPayApiClient vnPayApiClient;

    private String passwordHash;

    @BeforeEach
    void resetState() {
        // CASCADE dọn luôn mọi bảng phụ thuộc: auditoriums, seats, showtimes, showtime_seats, bookings, payments...
        jdbc.execute("TRUNCATE users, shows, venues RESTART IDENTITY CASCADE");
        // KHÔNG restart id của outbox: tin nhắn Kafka của test trước có thể đến muộn, eventId không được trùng
        jdbc.execute("TRUNCATE outbox_events, processed_events");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        mailSender.reset();
    }

    // ===================== Người dùng =====================

    protected long createUser(String email, Role role) {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);   // băm một lần, dùng lại cho mọi user test
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, email, passwordHash, "User " + email, role.name());
        return id;
    }

    protected List<Long> createUsers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createUser("user" + i + "@test.vn", Role.CUSTOMER));
        }
        return ids;
    }

    protected String login(String email) {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD), null);
        assertThat(response.getStatusCode().value()).as("login %s", email).isEqualTo(200);
        return response.getBody().at("/data/accessToken").asText();
    }

    protected String adminToken() {
        createUser("admin@test.vn", Role.ADMIN);
        return login("admin@test.vn");
    }

    protected String customerToken(String email) {
        createUser(email, Role.CUSTOMER);
        return login(email);
    }

    // ===================== HTTP =====================

    protected ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String token,
                                            Object... uriVariables) {
        return callWithHeaders(method, path, body, token, new HttpHeaders(), uriVariables);
    }

    protected ResponseEntity<JsonNode> callWithHeaders(HttpMethod method, String path, Object body, String token,
                                                       HttpHeaders headers, Object... uriVariables) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class, uriVariables);
    }

    // ===================== Dữ liệu danh mục =====================

    /** Suất chiếu đang mở bán, bắt đầu sau 2 ngày, phòng có các hàng A..(A+rows-1), mỗi hàng seatsPerRow ghế thường. */
    protected ShowtimeFixture createOnSaleShowtime(int rows, int seatsPerRow) {
        var venue = venueService.createVenue(new VenueRequest("Rạp Test", "Hà Nội", "1 Tràng Tiền"));
        var auditorium = venueService.createAuditorium(new AuditoriumRequest(venue.id(), "Phòng 1"));
        List<RowSpec> rowSpecs = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            rowSpecs.add(new RowSpec(String.valueOf((char) ('A' + r)), seatsPerRow, SeatType.STANDARD));
        }
        venueService.createSeatLayout(auditorium.id(), new SeatLayoutRequest(rowSpecs));

        var show = showService.create(new ShowRequest(ShowType.MOVIE, "Phim Hot", null, null, 120, null,
                null, null, null, ShowStatus.PUBLISHED));
        Instant start = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        var showtime = showtimeService.create(new CreateShowtimeRequest(show.id(), auditorium.id(), start, null,
                null, ShowtimeStatus.ON_SALE, Map.of(SeatType.STANDARD, new BigDecimal("90000"))));
        return new ShowtimeFixture(show.id(), auditorium.id(), showtime.id(), start);
    }

    /** id trong showtime_seats của ghế (hàng, số) trong suất chiếu. */
    protected long seatId(long showtimeId, String row, int number) {
        Long id = jdbc.queryForObject("""
                SELECT ss.id FROM showtime_seats ss JOIN seats s ON s.id = ss.seat_id
                 WHERE ss.showtime_id = ? AND s.row_label = ? AND s.seat_number = ?""",
                Long.class, showtimeId, row, number);
        return id;
    }

    protected String seatStatus(long showtimeSeatId) {
        return jdbc.queryForObject("SELECT status FROM showtime_seats WHERE id = ?", String.class, showtimeSeatId);
    }

    // ===================== Đặt vé / thanh toán =====================

    /** Giữ ghế qua API, trả về response. */
    protected ResponseEntity<JsonNode> holdSeats(String token, long showtimeId, String idempotencyKey,
                                                 Long... showtimeSeatIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return callWithHeaders(HttpMethod.POST, "/api/v1/bookings",
                Map.of("showtimeId", showtimeId, "showtimeSeatIds", List.of(showtimeSeatIds)), token, headers);
    }

    /** Giữ ghế, trả về mã đơn. */
    protected String holdBooking(String token, long showtimeId, String idempotencyKey, Long... showtimeSeatIds) {
        ResponseEntity<JsonNode> response = holdSeats(token, showtimeId, idempotencyKey, showtimeSeatIds);
        assertThat(response.getStatusCode().value()).as("hold %s", response.getBody()).isEqualTo(201);
        return response.getBody().at("/data/bookingCode").asText();
    }

    /** Tạo link thanh toán VNPay, trả về vnp_TxnRef. */
    protected String createPayment(String token, String bookingCode) {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/payments",
                Map.of("bookingCode", bookingCode), token);
        assertThat(response.getStatusCode().value()).as("create payment %s", response.getBody()).isEqualTo(201);
        return response.getBody().at("/data/txnRef").asText();
    }

    /**
     * Tham số VNPay gửi về (IPN/Return) đã ký đúng bằng hash secret của profile test.
     *
     * @param amountVnd số tiền VND (chưa nhân 100)
     */
    protected Map<String, String> signedVnPayParams(String txnRef, long amountVnd, String responseCode,
                                                    String transactionStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", paymentProperties.vnpay().tmnCode());
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_BankTranNo", "VNP14422574");
        params.put("vnp_CardType", "ATM");
        params.put("vnp_PayDate", "20261020100512");
        params.put("vnp_OrderInfo", "Thanh toan don ve");
        params.put("vnp_TransactionNo", "14422574");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TxnRef", txnRef);
        return sign(params);
    }

    /** Ký lại toàn bộ tham số (dùng sau khi sửa tham số trong test). */
    protected Map<String, String> sign(Map<String, String> params) {
        Map<String, String> unsigned = new HashMap<>(params);
        unsigned.remove("vnp_SecureHash");
        unsigned.remove("vnp_SecureHashType");
        String hash = VnPaySigner.hmacSha512(paymentProperties.vnpay().hashSecret(),
                VnPaySigner.canonicalQuery(unsigned));
        unsigned.put("vnp_SecureHashType", "HmacSHA512");
        unsigned.put("vnp_SecureHash", hash);
        return unsigned;
    }

    /** Giả lập VNPay gọi IPN (GET, tham số trên query string). Trả về RspCode. */
    protected String sendIpn(Map<String, String> params) {
        ResponseEntity<JsonNode> response = getWithQuery("/api/v1/payments/vnpay/ipn", params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().get("RspCode").asText();
    }

    protected ResponseEntity<JsonNode> getWithQuery(String path, Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        // Truyền URI đã encode sẵn: truyền String thì RestTemplate encode thêm lần nữa (%3A → %253A)
        return http.getForEntity(URI.create(http.getRootUri() + path + "?" + query), JsonNode.class);
    }

    protected String bookingStatus(String bookingCode) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_code = ?", String.class, bookingCode);
    }

    protected String paymentStatus(String txnRef) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE provider_txn_id = ?", String.class, txnRef);
    }

    protected int outboxCount(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Integer.class,
                eventType);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected record ShowtimeFixture(long showId, long auditoriumId, long showtimeId, Instant startTime) {
    }
}
