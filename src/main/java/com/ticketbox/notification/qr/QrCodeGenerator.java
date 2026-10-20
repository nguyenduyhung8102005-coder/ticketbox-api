package com.ticketbox.notification.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Sinh ảnh PNG mã QR bằng ZXing. */
@Component
public class QrCodeGenerator {

    private static final int DEFAULT_SIZE = 300;

    /**
     * Mức sửa lỗi M (~15%): vẫn quét được khi màn hình điện thoại bị lóa/xước nhẹ,
     * mà QR không quá dày với nội dung khoảng 50 ký tự.
     */
    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.MARGIN, 2);

    public byte[] png(String content) {
        return png(content, DEFAULT_SIZE);
    }

    public byte[] png(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, HINTS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("Không tạo được QR cho nội dung dài " + content.length(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
