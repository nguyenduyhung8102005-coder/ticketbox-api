package com.ticketbox.payment.vnpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Ký và xác minh theo đặc tả VNPay 2.1.0 (HMAC-SHA512).
 * <ul>
 *   <li>Thanh toán / IPN / Return: sắp xếp tham số theo tên tăng dần, bỏ giá trị rỗng, nối
 *       {@code ten=URLEncode(gia_tri)} bằng {@code &} (giống code mẫu Java của VNPay: URLEncoder US-ASCII,
 *       dấu cách thành {@code +}).</li>
 *   <li>API truy vấn / hoàn tiền: nối các trường theo thứ tự cố định bằng {@code |}.</li>
 * </ul>
 */
public final class VnPaySigner {

    private static final String ALGORITHM = "HmacSHA512";

    private VnPaySigner() {
    }

    /** @return chữ ký dạng hex chữ thường */
    public static String hmacSha512(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không khởi tạo được " + ALGORITHM, e);
        }
    }

    /** Chuỗi query chuẩn để ký (và cũng dùng làm query string của URL thanh toán). */
    public static String canonicalQuery(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        new TreeMap<>(params).forEach((name, value) -> {
            if (value != null && !value.isEmpty()) {
                joiner.add(encode(name) + "=" + encode(value));
            }
        });
        return joiner.toString();
    }

    /** So sánh hai chữ ký hex, không phân biệt hoa thường, thời gian hằng (chống timing attack). */
    public static boolean signatureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.US_ASCII);
    }
}
