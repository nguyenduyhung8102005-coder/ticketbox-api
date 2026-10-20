package com.ticketbox.payment.vnpay;

import com.ticketbox.payment.service.GatewayResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VnPayGatewayTest {

    private static final Instant CREATED = Instant.parse("2026-10-20T03:00:00Z");   // 10:00 giờ VN

    private final VnPayGateway gateway = new VnPayGateway(VnPayTestData.properties());

    @Test
    void paymentUrl_containsRequiredParams_withVnpayFormats() {
        Map<String, String> params = query(buildUrl());

        assertThat(params)
                .containsEntry("vnp_Version", "2.1.0")
                .containsEntry("vnp_Command", "pay")
                .containsEntry("vnp_TmnCode", "TESTTMN1")
                .containsEntry("vnp_Amount", "18000000")                // 180.000đ x 100
                .containsEntry("vnp_CurrCode", "VND")
                .containsEntry("vnp_CreateDate", "20261020100000")      // GMT+7
                .containsEntry("vnp_ExpireDate", "20261020101000")
                .containsEntry("vnp_OrderInfo", "Thanh toan don ve TB7KQ2M9XA4F")   // bỏ dấu tiếng Việt
                .containsEntry("vnp_BankCode", "NCB")
                .containsKey("vnp_SecureHash");
    }

    /** Chữ ký phải giống hệt cách code mẫu Java chính thức của VNPay tính (nếu lệch, VNPay báo "sai chữ ký"). */
    @Test
    void paymentUrl_signatureMatchesVnpayOfficialSample() throws Exception {
        String url = buildUrl();
        Map<String, String> params = query(url);
        String sentHash = params.remove("vnp_SecureHash");

        String[] sample = officialSample(params, VnPayTestData.SECRET);

        assertThat(sentHash).isEqualTo(sample[1]);
        assertThat(url).isEqualTo("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?" + sample[0]
                + "&vnp_SecureHash=" + sample[1]);
    }

    @Test
    void verifySignature_acceptsValidIpn_ignoringHashTypeAndCaseAndForeignParams() throws Exception {
        Map<String, String> ipn = signedIpn("00", "00");
        ipn.put("vnp_SecureHash", ipn.get("vnp_SecureHash").toUpperCase());
        ipn.put("utm_source", "facebook");

        assertThat(gateway.verifySignature(ipn)).isTrue();
    }

    @Test
    void verifySignature_rejectsTamperedOrMissingHash() throws Exception {
        Map<String, String> tampered = signedIpn("00", "00");
        tampered.put("vnp_Amount", "100");
        Map<String, String> missing = signedIpn("00", "00");
        missing.remove("vnp_SecureHash");

        assertThat(gateway.verifySignature(tampered)).isFalse();
        assertThat(gateway.verifySignature(missing)).isFalse();
    }

    @Test
    void parseCallback_paidOnlyWhenBothCodesAre00() throws Exception {
        GatewayResult paid = gateway.parseCallback(signedIpn("00", "00"));
        GatewayResult cancelled = gateway.parseCallback(signedIpn("24", "02"));

        assertThat(paid.paid()).isTrue();
        assertThat(paid.amount()).isEqualByComparingTo("180000");
        assertThat(paid.transactionNo()).isEqualTo("14422574");
        assertThat(paid.payDate()).isEqualTo(Instant.parse("2026-10-20T03:05:12Z"));
        assertThat(paid.raw()).doesNotContainKey("vnp_SecureHash");
        assertThat(cancelled.paid()).isFalse();
        assertThat(cancelled.responseCode()).isEqualTo("24");
    }

    @Test
    void parseCallback_missingAmount_isRejected() {
        assertThatThrownBy(() -> gateway.parseCallback(Map.of("vnp_TxnRef", "X")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountConversion() {
        assertThat(VnPayGateway.toVnpAmount(new BigDecimal("90000"))).isEqualTo("9000000");
        assertThat(VnPayGateway.fromVnpAmount("9000000")).isEqualByComparingTo("90000");
        assertThat(VnPayGateway.toAsciiOrderInfo("Thanh toán đơn vé #TB1 (Đà Nẵng)"))
                .isEqualTo("Thanh toan don ve TB1 Da Nang");
    }

    // ----- helpers -----

    private String buildUrl() {
        return gateway.buildPaymentUrl(new PaymentUrlRequest("TB7KQ2M9XA4FAB12CD", new BigDecimal("180000"),
                "Thanh toán đơn vé TB7KQ2M9XA4F", "127.0.0.1", CREATED, CREATED.plusSeconds(600), "NCB"));
    }

    private static Map<String, String> signedIpn(String responseCode, String transactionStatus) throws Exception {
        Map<String, String> ipn = new HashMap<>();
        ipn.put("vnp_TmnCode", "TESTTMN1");
        ipn.put("vnp_Amount", "18000000");
        ipn.put("vnp_BankCode", "NCB");
        ipn.put("vnp_PayDate", "20261020100512");
        ipn.put("vnp_OrderInfo", "Thanh toan don ve TB7KQ2M9XA4F");
        ipn.put("vnp_TransactionNo", "14422574");
        ipn.put("vnp_ResponseCode", responseCode);
        ipn.put("vnp_TransactionStatus", transactionStatus);
        ipn.put("vnp_TxnRef", "TB7KQ2M9XA4FAB12CD");
        ipn.put("vnp_SecureHash", officialSample(ipn, VnPayTestData.SECRET)[1]);
        ipn.put("vnp_SecureHashType", "HmacSHA512");
        return ipn;
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    /** Chép nguyên logic từ code mẫu Java của VNPay (vnpay_jsp/ajaxServlet): trả về [query, hash]. */
    private static String[] officialSample(Map<String, String> vnpParams, String secret) throws Exception {
        List<String> fieldNames = new ArrayList<>(vnpParams.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = vnpParams.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                hashData.append(fieldName).append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString())).append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                if (itr.hasNext()) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        }
        javax.crypto.Mac hmac512 = javax.crypto.Mac.getInstance("HmacSHA512");
        hmac512.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA512"));
        byte[] result = hmac512.doFinal(hashData.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(2 * result.length);
        for (byte b : result) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return new String[]{query.toString(), sb.toString()};
    }
}
