package com.ticketbox.payment.vnpay;

import com.ticketbox.payment.service.GatewayResult;
import com.ticketbox.payment.service.PaymentProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phần "trình duyệt" của VNPay: dựng URL thanh toán đã ký, xác minh chữ ký IPN / Return URL.
 * (Phần gọi API server-to-server nằm ở {@link VnPayApiClient}.)
 */
@Component
public class VnPayGateway {

    public static final String SECURE_HASH = "vnp_SecureHash";
    public static final String SECURE_HASH_TYPE = "vnp_SecureHashType";
    public static final String TXN_REF = "vnp_TxnRef";

    private final PaymentProperties.VnPay config;

    public VnPayGateway(PaymentProperties properties) {
        this.config = properties.vnpay();
    }

    public String buildPaymentUrl(PaymentUrlRequest request) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", config.version());
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", config.tmnCode());
        params.put("vnp_Amount", toVnpAmount(request.amount()));
        params.put("vnp_CurrCode", "VND");
        params.put(TXN_REF, request.txnRef());
        params.put("vnp_OrderInfo", toAsciiOrderInfo(request.orderInfo()));
        params.put("vnp_OrderType", config.orderType());
        params.put("vnp_Locale", config.locale());
        params.put("vnp_ReturnUrl", config.returnUrl());
        params.put("vnp_IpAddr", request.clientIp());
        params.put("vnp_CreateDate", VnPayDates.format(request.createdAt()));
        params.put("vnp_ExpireDate", VnPayDates.format(request.expireAt()));
        if (request.bankCode() != null && !request.bankCode().isBlank()) {
            params.put("vnp_BankCode", request.bankCode());
        }
        String query = VnPaySigner.canonicalQuery(params);
        String signature = VnPaySigner.hmacSha512(config.hashSecret(), query);
        return config.payUrl() + "?" + query + "&" + SECURE_HASH + "=" + signature;
    }

    /**
     * Xác minh chữ ký của tham số VNPay gửi về (IPN hoặc Return URL): bỏ vnp_SecureHash, vnp_SecureHashType,
     * ký lại phần còn lại (chỉ các tham số vnp_*) và so sánh.
     */
    public boolean verifySignature(Map<String, String> params) {
        String received = params.get(SECURE_HASH);
        if (received == null || received.isBlank()) {
            return false;
        }
        Map<String, String> fields = new HashMap<>();
        params.forEach((name, value) -> {
            if (name.startsWith("vnp_") && !SECURE_HASH.equals(name) && !SECURE_HASH_TYPE.equals(name)) {
                fields.put(name, value);
            }
        });
        String expected = VnPaySigner.hmacSha512(config.hashSecret(), VnPaySigner.canonicalQuery(fields));
        return VnPaySigner.signatureEquals(expected, received);
    }

    /**
     * Đọc kết quả từ tham số IPN/Return (gọi SAU khi đã xác minh chữ ký).
     * Thành công khi CẢ vnp_ResponseCode và vnp_TransactionStatus đều là "00".
     *
     * @throws IllegalArgumentException nếu thiếu mã giao dịch hoặc số tiền sai định dạng
     */
    public GatewayResult parseCallback(Map<String, String> params) {
        String txnRef = params.get(TXN_REF);
        String rawAmount = params.get("vnp_Amount");
        if (txnRef == null || txnRef.isBlank() || rawAmount == null) {
            throw new IllegalArgumentException("Thiếu vnp_TxnRef hoặc vnp_Amount");
        }
        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        boolean paid = "00".equals(responseCode) && "00".equals(transactionStatus);
        Map<String, String> raw = new TreeMap<>(params);
        raw.remove(SECURE_HASH);
        return new GatewayResult(
                txnRef,
                fromVnpAmount(rawAmount),
                paid,
                responseCode,
                transactionStatus,
                emptyToNull(params.get("vnp_TransactionNo")),
                emptyToNull(params.get("vnp_BankCode")),
                VnPayDates.parse(params.get("vnp_PayDate")),
                raw);
    }

    /** VNPay nhận số tiền nhân 100, không có phần thập phân: 90.000đ → "9000000". */
    static String toVnpAmount(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact().toString();
    }

    static BigDecimal fromVnpAmount(String value) {
        try {
            return new BigDecimal(value.trim()).movePointLeft(2);   // so sánh bằng compareTo, không dùng equals
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("vnp_Amount sai định dạng: " + value, e);
        }
    }

    /** vnp_OrderInfo phải là tiếng Việt KHÔNG dấu, không ký tự đặc biệt. */
    static String toAsciiOrderInfo(String text) {
        String noAccent = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        String cleaned = noAccent.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
