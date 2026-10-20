package com.ticketbox.payment.vnpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.payment.service.PaymentLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Kiểm tra định dạng request/chữ ký của API querydr/refund bằng server HTTP giả (không gọi VNPay thật). */
class VnPayApiClientTest {

    private static final String API = "https://vnpay.test/api";
    private static final Instant NOW = Instant.parse("2026-10-20T03:20:00Z");
    private static final Instant PAYMENT_CREATED = Instant.parse("2026-10-20T03:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private VnPayApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VnPayApiClient(builder.build(), VnPayTestData.properties(), mock(PaymentLogWriter.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void queryTransaction_signsRequestInDocumentedOrder_andParsesSignedResponse() {
        AtomicReference<Map<String, String>> sent = new AtomicReference<>();
        server.expect(requestTo(API))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.set(readBody((MockClientHttpRequest) request)))
                .andRespond(withSuccess(json(signedResponse(VnPayApiClient.QUERY_RESPONSE_FIELDS,
                        queryResponse("00", "00"))), MediaType.APPLICATION_JSON));

        VnPayQueryResult result = client.queryTransaction("TXN123", PAYMENT_CREATED);

        Map<String, String> body = sent.get();
        assertThat(body).containsEntry("vnp_Command", "querydr")
                .containsEntry("vnp_TxnRef", "TXN123")
                .containsEntry("vnp_TransactionDate", "20261020100000")   // = vnp_CreateDate lúc thanh toán
                .containsEntry("vnp_CreateDate", "20261020102000");
        String expectedHash = VnPaySigner.hmacSha512(VnPayTestData.SECRET, String.join("|",
                body.get("vnp_RequestId"), "2.1.0", "querydr", "TESTTMN1", "TXN123", "20261020100000",
                "20261020102000", "127.0.0.1", body.get("vnp_OrderInfo")));
        assertThat(body.get("vnp_SecureHash")).isEqualTo(expectedHash);
        assertThat(body.get("vnp_RequestId")).hasSizeLessThanOrEqualTo(32);

        assertThat(result.paid()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("180000");
        assertThat(result.transactionNo()).isEqualTo("14422574");
        server.verify();
    }

    @Test
    void queryTransaction_responseWithWrongSignature_isRejected() {
        Map<String, String> response = signedResponse(VnPayApiClient.QUERY_RESPONSE_FIELDS, queryResponse("00", "00"));
        response.put("vnp_TransactionStatus", "02");   // bị sửa sau khi ký
        server.expect(requestTo(API)).andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.queryTransaction("TXN123", PAYMENT_CREATED))
                .isInstanceOf(VnPayApiException.class)
                .hasMessageContaining("sai chữ ký");
    }

    @Test
    void queryResult_statusHelpers() {
        assertThat(result("91", null).notFound()).isTrue();
        assertThat(result("00", "01").incomplete()).isTrue();
        assertThat(result("00", "02").failed()).isTrue();
        assertThat(result("00", "05").refundInProgressOrDone()).isTrue();
        assertThat(result("00", "00").paid()).isTrue();
        assertThat(result("91", null).paid()).isFalse();
    }

    @Test
    void refund_signsRequestInDocumentedOrder() {
        AtomicReference<Map<String, String>> sent = new AtomicReference<>();
        Map<String, String> response = new LinkedHashMap<>(queryResponse("00", "05"));
        response.put("vnp_Command", "refund");
        server.expect(requestTo(API))
                .andExpect(request -> sent.set(readBody((MockClientHttpRequest) request)))
                .andRespond(withSuccess(json(signedResponse(VnPayApiClient.REFUND_RESPONSE_FIELDS, response)),
                        MediaType.APPLICATION_JSON));

        VnPayRefundResult result = client.refund("TXN123", new BigDecimal("180000"), "14422574", PAYMENT_CREATED);

        Map<String, String> body = sent.get();
        assertThat(body).containsEntry("vnp_Command", "refund")
                .containsEntry("vnp_TransactionType", "02")
                .containsEntry("vnp_Amount", "18000000")
                .containsEntry("vnp_TransactionNo", "14422574");
        String expectedHash = VnPaySigner.hmacSha512(VnPayTestData.SECRET, String.join("|",
                body.get("vnp_RequestId"), "2.1.0", "refund", "TESTTMN1", "02", "TXN123", "18000000", "14422574",
                "20261020100000", body.get("vnp_CreateBy"), "20261020102000", "127.0.0.1", body.get("vnp_OrderInfo")));
        assertThat(body.get("vnp_SecureHash")).isEqualTo(expectedHash);
        assertThat(result.accepted()).isTrue();
    }

    // ----- helpers -----

    private static VnPayQueryResult result(String responseCode, String transactionStatus) {
        return new VnPayQueryResult("TXN", responseCode, "", transactionStatus, null, null, null, null, Map.of());
    }

    private static Map<String, String> queryResponse(String responseCode, String transactionStatus) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("vnp_ResponseId", "a1b2c3");
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", responseCode);
        response.put("vnp_Message", "QueryDR Success");
        response.put("vnp_TmnCode", "TESTTMN1");
        response.put("vnp_TxnRef", "TXN123");
        response.put("vnp_Amount", "18000000");
        response.put("vnp_BankCode", "NCB");
        response.put("vnp_PayDate", "20261020100512");
        response.put("vnp_TransactionNo", "14422574");
        response.put("vnp_TransactionType", "01");
        response.put("vnp_TransactionStatus", transactionStatus);
        response.put("vnp_OrderInfo", "Truy van giao dich TXN123");
        return response;
    }

    private static Map<String, String> signedResponse(List<String> fields, Map<String, String> response) {
        Map<String, String> signed = new LinkedHashMap<>(response);
        String data = String.join("|", fields.stream().map(f -> signed.getOrDefault(f, "")).toList());
        signed.put("vnp_SecureHash", VnPaySigner.hmacSha512(VnPayTestData.SECRET, data));
        return signed;
    }

    private Map<String, String> readBody(MockClientHttpRequest request) {
        try {
            return objectMapper.readValue(request.getBodyAsString(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Map<String, String> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
