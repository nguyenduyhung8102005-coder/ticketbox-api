package com.ticketbox.notification.qr;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class QrCodeGeneratorTest {

    @Test
    void generatedPng_decodesBackToSameContent() throws Exception {
        String content = "TBK1.MTIzLjQ1LjY3OA.H6rtyJQA8SfFRBjmaWAK9A";

        byte[] png = new QrCodeGenerator().png(content);

        assertThat(decode(png)).isEqualTo(content);
    }

    /** Dùng chung cho integration test: đọc lại nội dung QR trong email. */
    public static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap).getText();
    }
}
