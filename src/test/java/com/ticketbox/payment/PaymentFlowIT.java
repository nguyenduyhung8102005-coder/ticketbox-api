package com.ticketbox.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.booking.service.BookingExpiryService;
import com.ticketbox.booking.ticket.TicketCodeSigner;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.payment.vnpay.VnPayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thanh toán VNPay end-to-end với PostgreSQL thật: tạo link → IPN (giả lập VNPay, ký đúng secret test)
 * → đơn CONFIRMED, ghế SOLD, vé có chữ ký, outbox. Kèm các ca khó: IPN lặp lại, IPN song song, chữ ký giả,
 * sai số tiền, trả tiền sau khi hết hạn giữ chỗ, trả tiền 2 lần.
 */
class PaymentFlowIT extends AbstractIntegrationTest {

    private static final long PRICE = 90000;

    @Autowired
    private BookingExpiryService expiryService;
    @Autowired
    private VnPayGateway vnPayGateway;
    @Autowired
    private TicketCodeSigner ticketCodeSigner;

    private ShowtimeFixture fixture;
    private long a1;
    private long a2;
    private String alice;

    @BeforeEach
    void setUp() {
        fixture = createOnSaleShowtime(2, 5);
        a1 = seatId(fixture.showtimeId(), "A", 1);
        a2 = seatId(fixture.showtimeId(), "A", 2);
        alice = customerToken("alice@test.vn");
    }

    // ===================== Tạo link =====================

    @Test
    void createPayment_returnsUrlSignedForVnpay() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1, a2);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingCode", code), alice);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String url = response.getBody().at("/data/paymentUrl").asText();
        Map<String, String> params = query(url);
        assertThat(url).startsWith(paymentProperties.vnpay().payUrl());
        assertThat(params).containsEntry("vnp_Amount", String.valueOf(2 * PRICE * 100))
                .containsEntry("vnp_TxnRef", response.getBody().at("/data/txnRef").asText());
        assertThat(vnPayGateway.verifySignature(params)).as("chữ ký URL hợp lệ").isTrue();
        assertThat(paymentStatus(params.get("vnp_TxnRef"))).isEqualTo("INITIATED");
    }

    @Test
    void createPayment_forAnotherUsersBooking_returns404() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String bob = customerToken("bob@test.vn");

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingCode", code), bob);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void createPayment_forCancelledBooking_returns409() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        call(HttpMethod.DELETE, "/api/v1/bookings/" + code, null, alice);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingCode", code), alice);

        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("BOOKING_NOT_PAYABLE");
    }

    // ===================== IPN =====================

    @Test
    void ipnSuccess_confirmsBooking_sellsSeats_issuesSignedTickets_andWritesOutboxInSameTransaction() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1, a2);
        String txn = createPayment(alice, code);

        assertThat(sendIpn(signedVnPayParams(txn, 2 * PRICE, "00", "00"))).isEqualTo("00");

        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(a1)).isEqualTo("SOLD");
        assertThat(seatStatus(a2)).isEqualTo("SOLD");
        assertThat(paymentStatus(txn)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT provider_transaction_no FROM payments WHERE provider_txn_id = ?",
                String.class, txn)).isEqualTo("14422574");
        assertThat(outboxCount(EventTypes.BOOKING_CONFIRMED)).isEqualTo(1);

        List<String> tickets = jdbc.queryForList("""
                SELECT i.ticket_code FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                 WHERE b.booking_code = ?""", String.class, code);
        assertThat(tickets).hasSize(2).allSatisfy(t -> assertThat(ticketCodeSigner.verify(t)).isPresent());

        // Khách xem đơn thấy mã vé
        JsonNode booking = call(HttpMethod.GET, "/api/v1/bookings/" + code, null, alice).getBody().get("data");
        assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(booking.at("/seats/0/ticketCode").asText()).startsWith("TBK1.");
        // Nhật ký IPN được lưu nguyên văn, gắn với giao dịch
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_logs l JOIN payments p ON p.id = l.payment_id
                 WHERE p.provider_txn_id = ? AND l.kind = 'IPN' AND l.result = '00'""", Integer.class, txn))
                .isEqualTo(1);
    }

    @Test
    void ipnRepeated_isIdempotent_02_noDuplicateEvents() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        Map<String, String> ipn = signedVnPayParams(txn, PRICE, "00", "00");

        assertThat(sendIpn(ipn)).isEqualTo("00");
        assertThat(sendIpn(ipn)).isEqualTo("02");
        assertThat(sendIpn(ipn)).isEqualTo("02");

        assertThat(outboxCount(EventTypes.BOOKING_CONFIRMED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_items WHERE ticket_code IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }

    /** VNPay gọi lại IPN dồn dập (timeout phía mình) song song với chính nó: chỉ đúng 1 lần được xử lý. */
    @Test
    void ipnConcurrent_20Calls_processedExactlyOnce() throws Exception {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        Map<String, String> ipn = signedVnPayParams(txn, PRICE, "00", "00");

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return sendIpn(ipn);
            }));
        }
        start.countDown();
        List<String> codes = new ArrayList<>();
        for (Future<String> f : futures) {
            codes.add(f.get());
        }
        executor.shutdown();

        assertThat(codes).filteredOn("00"::equals).hasSize(1);
        assertThat(codes).filteredOn("02"::equals).hasSize(threads - 1);
        assertThat(outboxCount(EventTypes.BOOKING_CONFIRMED)).isEqualTo(1);
        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
    }

    @Test
    void ipnWithForgedSignature_returns97_andChangesNothing() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        Map<String, String> forged = new HashMap<>(signedVnPayParams(txn, PRICE, "24", "02"));
        forged.put("vnp_ResponseCode", "00");          // kẻ gian sửa "thất bại" thành "thành công"
        forged.put("vnp_TransactionStatus", "00");

        assertThat(sendIpn(forged)).isEqualTo("97");

        assertThat(paymentStatus(txn)).isEqualTo("INITIATED");
        assertThat(bookingStatus(code)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_logs WHERE result = '97'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void ipnWithWrongAmount_returns04() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);

        assertThat(sendIpn(signedVnPayParams(txn, 1000, "00", "00"))).isEqualTo("04");
        assertThat(paymentStatus(txn)).isEqualTo("INITIATED");
    }

    @Test
    void ipnForUnknownTransaction_returns01() {
        assertThat(sendIpn(signedVnPayParams("TBNOTEXIST000000", PRICE, "00", "00"))).isEqualTo("01");
    }

    @Test
    void ipnFailed_keepsBookingPending_soCustomerCanRetryPayment() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String first = createPayment(alice, code);

        assertThat(sendIpn(signedVnPayParams(first, PRICE, "24", "02"))).isEqualTo("00");   // 00 = đã nhận kết quả

        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(bookingStatus(code)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(a1)).isEqualTo("HELD");
        String second = createPayment(alice, code);
        assertThat(second).isNotEqualTo(first);
        assertThat(sendIpn(signedVnPayParams(second, PRICE, "00", "00"))).isEqualTo("00");
        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
    }

    // ===================== Ca khó: trả tiền khi giữ chỗ đã hết hạn =====================

    @Test
    void latePayment_seatsStillFree_bookingIsConfirmedAnyway() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        expireNow(code);
        assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");

        assertThat(sendIpn(signedVnPayParams(txn, PRICE, "00", "00"))).isEqualTo("00");

        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(a1)).isEqualTo("SOLD");
        assertThat(paymentStatus(txn)).isEqualTo("SUCCESS");
    }

    @Test
    void latePayment_seatTakenBySomeoneElse_bookingGoesToRefund() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1, a2);
        String txn = createPayment(alice, code);
        expireNow(code);
        String bob = customerToken("bob@test.vn");
        String bobCode = holdBooking(bob, fixture.showtimeId(), "bob-key-00001", a2);   // Bob lấy A2

        assertThat(sendIpn(signedVnPayParams(txn, 2 * PRICE, "00", "00"))).isEqualTo("00");

        assertThat(bookingStatus(code)).isEqualTo("REFUND_PENDING");
        assertThat(paymentStatus(txn)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT refund_reason FROM payments WHERE provider_txn_id = ?",
                String.class, txn)).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(outboxCount(EventTypes.PAYMENT_REFUND_REQUESTED)).isEqualTo(1);
        assertThat(outboxCount(EventTypes.BOOKING_CONFIRMED)).isZero();
        // Không đụng tới ghế của Bob, cũng không lấy A1 một mình (all-or-nothing)
        assertThat(seatStatus(a2)).isEqualTo("HELD");
        assertThat(bookingStatus(bobCode)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
    }

    @Test
    void customerPaysTwice_secondPaymentIsMarkedForRefund() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String tab1 = createPayment(alice, code);
        String tab2 = createPayment(alice, code);

        assertThat(sendIpn(signedVnPayParams(tab1, PRICE, "00", "00"))).isEqualTo("00");
        assertThat(sendIpn(signedVnPayParams(tab2, PRICE, "00", "00"))).isEqualTo("00");

        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(tab1)).isEqualTo("SUCCESS");
        assertThat(paymentStatus(tab2)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT refund_reason FROM payments WHERE provider_txn_id = ?",
                String.class, tab2)).isEqualTo("DUPLICATE_PAYMENT");
    }

    // ===================== Return URL =====================

    @Test
    void returnUrl_onlyDisplaysStatus_neverChangesIt() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        Map<String, String> params = signedVnPayParams(txn, PRICE, "00", "00");

        JsonNode beforeIpn = getWithQuery("/api/v1/payments/vnpay/return", params).getBody().get("data");
        assertThat(beforeIpn.get("paymentStatus").asText()).isEqualTo("INITIATED");
        assertThat(beforeIpn.get("gatewaySuccess").asBoolean()).isTrue();
        assertThat(bookingStatus(code)).as("Return URL không xác nhận đơn").isEqualTo("PENDING_PAYMENT");

        sendIpn(params);
        JsonNode afterIpn = getWithQuery("/api/v1/payments/vnpay/return", params).getBody().get("data");
        assertThat(afterIpn.get("bookingStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(afterIpn.get("bookingCode").asText()).isEqualTo(code);
    }

    @Test
    void returnUrl_forgedSignature_returns400() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        Map<String, String> params = new HashMap<>(signedVnPayParams(txn, PRICE, "00", "00"));
        params.put("vnp_Amount", "1");

        ResponseEntity<JsonNode> response = getWithQuery("/api/v1/payments/vnpay/return", params);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("PAYMENT_SIGNATURE_INVALID");
    }

    // ===================== Soát vé =====================

    @Test
    void adminVerifiesTicket_validCode_andForgedCode() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        sendIpn(signedVnPayParams(createPayment(alice, code), PRICE, "00", "00"));
        String ticket = jdbc.queryForObject("SELECT ticket_code FROM booking_items WHERE ticket_code IS NOT NULL",
                String.class);
        String admin = adminToken();

        JsonNode valid = call(HttpMethod.POST, "/api/v1/admin/tickets/verify", Map.of("ticketCode", ticket), admin)
                .getBody().get("data");
        // Sửa ký tự ĐẦU của phần chữ ký (ký tự cuối base64 có bit đệm, sửa có thể không đổi giá trị)
        String[] parts = ticket.split("\\.");
        String forgedCode = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? 'B' : 'A')
                + parts[2].substring(1);
        JsonNode forged = call(HttpMethod.POST, "/api/v1/admin/tickets/verify",
                Map.of("ticketCode", forgedCode), admin).getBody().get("data");

        assertThat(valid.get("valid").asBoolean()).isTrue();
        assertThat(valid.get("seatLabel").asText()).isEqualTo("A1");
        assertThat(valid.get("bookingCode").asText()).isEqualTo(code);
        assertThat(forged.get("valid").asBoolean()).isFalse();
        assertThat(forged.get("result").asText()).isEqualTo("SIGNATURE_INVALID");
        // Khách thường không được soát vé
        assertThat(call(HttpMethod.POST, "/api/v1/admin/tickets/verify", Map.of("ticketCode", ticket), alice)
                .getStatusCode().value()).isEqualTo(403);
    }

    // ===================== helpers =====================

    /** Giả lập đã quá 10 phút giữ chỗ và job hết hạn đã chạy. */
    private void expireNow(String bookingCode) {
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_code = ?",
                bookingCode);
        assertThat(expiryService.expireDueBookings()).isEqualTo(1);
        assertThat(bookingStatus(bookingCode)).isEqualTo("EXPIRED");
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }
}
