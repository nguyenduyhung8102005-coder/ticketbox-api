package com.ticketbox.payment.vnpay;

import com.ticketbox.payment.domain.PaymentLogKind;
import com.ticketbox.payment.domain.PaymentProvider;
import com.ticketbox.payment.service.PaymentLogWriter;
import com.ticketbox.payment.service.PaymentProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gọi API server-to-server của VNPay (merchant_webapi): truy vấn giao dịch (querydr) và hoàn tiền (refund).
 * <p>
 * Chữ ký request/response = HMAC-SHA512 của các trường nối bằng {@code |} theo thứ tự cố định trong tài liệu VNPay.
 * Phản hồi sai chữ ký bị coi là lỗi (không tin dữ liệu chưa xác minh, kể cả qua HTTPS).
 */
@Component
public class VnPayApiClient {

    static final List<String> QUERY_RESPONSE_FIELDS = List.of(
            "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode", "vnp_TxnRef",
            "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
            "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode", "vnp_PromotionAmount");

    static final List<String> REFUND_RESPONSE_FIELDS = List.of(
            "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode", "vnp_TxnRef",
            "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
            "vnp_TransactionStatus", "vnp_OrderInfo");

    /** Hoàn toàn bộ số tiền. 03 = hoàn một phần. */
    private static final String FULL_REFUND = "02";
    private static final String CREATED_BY = "ticketbox-system";

    private final RestClient restClient;
    private final PaymentProperties.VnPay config;
    private final PaymentLogWriter logWriter;
    private final Clock clock;

    public VnPayApiClient(@Qualifier("vnPayRestClient") RestClient restClient, PaymentProperties properties,
                          PaymentLogWriter logWriter, Clock clock) {
        this.restClient = restClient;
        this.config = properties.vnpay();
        this.logWriter = logWriter;
        this.clock = clock;
    }

    /**
     * @param transactionDate thời điểm tạo giao dịch thanh toán gốc (đúng giá trị đã gửi làm vnp_CreateDate)
     */
    public VnPayQueryResult queryTransaction(String txnRef, Instant transactionDate) {
        String requestId = newRequestId();
        String createDate = VnPayDates.format(clock.instant());
        String orderInfo = "Truy van giao dich " + txnRef;

        Map<String, String> request = new LinkedHashMap<>();
        request.put("vnp_RequestId", requestId);
        request.put("vnp_Version", config.version());
        request.put("vnp_Command", "querydr");
        request.put("vnp_TmnCode", config.tmnCode());
        request.put("vnp_TxnRef", txnRef);
        request.put("vnp_OrderInfo", orderInfo);
        request.put("vnp_TransactionDate", VnPayDates.format(transactionDate));
        request.put("vnp_CreateDate", createDate);
        request.put("vnp_IpAddr", config.serverIp());
        request.put("vnp_SecureHash", sign(
                requestId, config.version(), "querydr", config.tmnCode(), txnRef,
                request.get("vnp_TransactionDate"), createDate, config.serverIp(), orderInfo));

        Map<String, String> response = post(request, PaymentLogKind.QUERY, txnRef);
        verifyResponse(response, QUERY_RESPONSE_FIELDS);
        return new VnPayQueryResult(
                txnRef,
                response.get("vnp_ResponseCode"),
                response.get("vnp_Message"),
                response.get("vnp_TransactionStatus"),
                blankToNull(response.get("vnp_TransactionNo")),
                blankToNull(response.get("vnp_BankCode")),
                parseAmount(response.get("vnp_Amount")),
                VnPayDates.parse(response.get("vnp_PayDate")),
                response);
    }

    /**
     * Hoàn toàn bộ tiền của một giao dịch đã thành công.
     *
     * @param transactionNo   vnp_TransactionNo nhận được trong IPN
     * @param transactionDate thời điểm tạo giao dịch thanh toán gốc (vnp_CreateDate lúc thanh toán)
     */
    public VnPayRefundResult refund(String txnRef, BigDecimal amount, String transactionNo, Instant transactionDate) {
        String requestId = newRequestId();
        String createDate = VnPayDates.format(clock.instant());
        String orderInfo = "Hoan tien giao dich " + txnRef;
        String vnpAmount = VnPayGateway.toVnpAmount(amount);
        String txnNo = transactionNo == null ? "" : transactionNo;

        Map<String, String> request = new LinkedHashMap<>();
        request.put("vnp_RequestId", requestId);
        request.put("vnp_Version", config.version());
        request.put("vnp_Command", "refund");
        request.put("vnp_TmnCode", config.tmnCode());
        request.put("vnp_TransactionType", FULL_REFUND);
        request.put("vnp_TxnRef", txnRef);
        request.put("vnp_Amount", vnpAmount);
        request.put("vnp_OrderInfo", orderInfo);
        request.put("vnp_TransactionNo", txnNo);
        request.put("vnp_TransactionDate", VnPayDates.format(transactionDate));
        request.put("vnp_CreateBy", CREATED_BY);
        request.put("vnp_CreateDate", createDate);
        request.put("vnp_IpAddr", config.serverIp());
        request.put("vnp_SecureHash", sign(
                requestId, config.version(), "refund", config.tmnCode(), FULL_REFUND, txnRef, vnpAmount, txnNo,
                request.get("vnp_TransactionDate"), CREATED_BY, createDate, config.serverIp(), orderInfo));

        Map<String, String> response = post(request, PaymentLogKind.REFUND, txnRef);
        verifyResponse(response, REFUND_RESPONSE_FIELDS);
        return new VnPayRefundResult(response.get("vnp_ResponseCode"), response.get("vnp_Message"), response);
    }

    private Map<String, String> post(Map<String, String> request, PaymentLogKind kind, String txnRef) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri("")                                   // = app.payment.vnpay.api-url (baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            logWriter.log(PaymentProvider.VNPAY, kind, txnRef, Map.of("request", request, "error", e.toString()),
                    "HTTP_ERROR");
            throw new VnPayApiException("Gọi VNPay " + kind + " lỗi: " + e.getMessage(), e);
        }
        Map<String, String> response = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((key, value) -> response.put(key, value == null ? null : String.valueOf(value)));
        }
        logWriter.log(PaymentProvider.VNPAY, kind, txnRef, Map.of("request", request, "response", response),
                response.get("vnp_ResponseCode"));
        return response;
    }

    private void verifyResponse(Map<String, String> response, List<String> fields) {
        String received = response.get(VnPayGateway.SECURE_HASH);
        if (received == null || received.isBlank()) {
            throw new VnPayApiException("Phản hồi VNPay không có chữ ký, mã " + response.get("vnp_ResponseCode"));
        }
        String[] values = fields.stream().map(f -> {
            String value = response.get(f);
            return value == null ? "" : value;
        }).toArray(String[]::new);
        if (!VnPaySigner.signatureEquals(sign(values), received)) {
            throw new VnPayApiException("Phản hồi VNPay sai chữ ký, mã " + response.get("vnp_ResponseCode"));
        }
    }

    private String sign(String... values) {
        return VnPaySigner.hmacSha512(config.hashSecret(), String.join("|", values));
    }

    /** Tối đa 32 ký tự chữ và số, duy nhất cho mỗi request. */
    private static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return VnPayGateway.fromVnpAmount(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
