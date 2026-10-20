package com.ticketbox.booking.ticket;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Sinh và xác minh mã vé (nội dung mã QR).
 *
 * <pre>
 * TBK1.MTIzLjQ1LjY3OA.q2Jx0n8yZ6m1o3VbX9kQeA
 * │    │              └── HMAC-SHA256(khóa bí mật, "TBK1." + phần giữa), lấy 16 byte, base64url
 * │    └── base64url("bookingItemId.showtimeId.showtimeSeatId")
 * └── phiên bản định dạng (đổi thuật toán sau này thì tăng TBK2, vé cũ vẫn đọc được)
 * </pre>
 * <p>
 * Nhân viên soát vé xác minh được NGAY TẠI CHỖ, không cần mạng: chỉ cần tính lại HMAC. Người ngoài không có
 * khóa thì không sửa được nội dung (đổi số ghế, đổi suất) hay tự tạo vé giả. 16 byte = 128 bit chữ ký,
 * đủ chống đoán mò, mà mã vẫn ngắn (khoảng 50 ký tự) để QR thưa, dễ quét.
 * <p>
 * Hạn chế: máy soát vé phải giữ khóa bí mật. Nếu cần máy soát vé chỉ giữ khóa công khai thì chuyển sang
 * chữ ký bất đối xứng (Ed25519/ECDSA), đổi lại mã dài hơn.
 */
@Component
public class TicketCodeSigner {

    static final String VERSION = "TBK1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int SIGNATURE_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public TicketCodeSigner(TicketProperties properties) {
        String secret = properties.signingSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("app.ticket.signing-secret phải có tối thiểu 32 ký tự");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(TicketClaims claims) {
        String body = claims.bookingItemId() + "." + claims.showtimeId() + "." + claims.showtimeSeatId();
        String encodedBody = ENCODER.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String signedPart = VERSION + "." + encodedBody;
        return signedPart + "." + ENCODER.encodeToString(signature(signedPart));
    }

    /** @return nội dung vé nếu chữ ký hợp lệ; rỗng nếu mã sai định dạng hoặc bị sửa */
    public Optional<TicketClaims> verify(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String[] parts = code.trim().split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            byte[] expected = signature(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            // So sánh thời gian hằng: không lộ thông tin qua thời gian phản hồi (timing attack)
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            String[] ids = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8).split("\\.");
            if (ids.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new TicketClaims(Long.parseLong(ids[0]), Long.parseLong(ids[1]),
                    Long.parseLong(ids[2])));
        } catch (IllegalArgumentException e) {   // base64 hoặc số sai định dạng
            return Optional.empty();
        }
    }

    private byte[] signature(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Arrays.copyOf(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)), SIGNATURE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không khởi tạo được " + ALGORITHM, e);
        }
    }
}
