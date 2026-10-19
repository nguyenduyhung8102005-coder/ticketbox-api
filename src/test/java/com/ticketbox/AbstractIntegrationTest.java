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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lớp cha cho integration test: PostgreSQL + Redis thật (Testcontainers), ứng dụng chạy trên cổng ngẫu nhiên.
 * Cấu hình annotation GIỐNG HỆT InfrastructureIT để Spring tái sử dụng một context (và một cặp container)
 * cho mọi lớp test, thay vì khởi động lại cho từng lớp.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
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

    private String passwordHash;

    @BeforeEach
    void resetState() {
        // CASCADE dọn luôn mọi bảng phụ thuộc: auditoriums, seats, showtimes, showtime_seats, bookings...
        jdbc.execute("TRUNCATE users, shows, venues RESTART IDENTITY CASCADE");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
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

    protected record ShowtimeFixture(long showId, long auditoriumId, long showtimeId, Instant startTime) {
    }
}
