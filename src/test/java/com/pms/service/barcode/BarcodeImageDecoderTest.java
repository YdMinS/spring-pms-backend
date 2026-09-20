package com.pms.service.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BarcodeImageDecoder} 단위 테스트 (FEATURE_2609_65).
 *
 * <p>픽스처 파일을 두지 않는다 — 테스트가 ZXing {@code MultiFormatWriter} 로 이미지를 그려서 같은 값으로
 * 되읽는다. 회전 경로도 그린 이미지를 돌려 검증한다.</p>
 *
 * <p>⚠️ {@code 8801234567893} 의 마지막 {@code 3} 은 임의 숫자가 아니라 <b>체크디지트</b>다.
 * {@code EAN13Writer} 는 13자리를 받으면 검산해서 안 맞으면 예외를 던진다. 계산:
 * 홀수자리 합 {@code 8+0+2+4+6+8=28} + 짝수자리 합 {@code (8+1+3+5+7+9)×3=99} = {@code 127}
 * → {@code (10 − 127 mod 10) mod 10 = 3}. 🔴 값을 바꾸려면 체크디지트를 다시 계산할 것 —
 * 그 검산이 이 기능의 신뢰 근거다(PLAN §2).</p>
 */
class BarcodeImageDecoderTest {

    private static final String EAN13 = "8801234567893";

    private final BarcodeImageDecoder decoder = new BarcodeImageDecoder();

    @Test
    void decode_ean13_returnsSameDigits() throws Exception {
        Optional<DecodedBarcode> result = decoder.decode(png(BarcodeFormat.EAN_13, EAN13, 400, 150));

        assertThat(result).isPresent();
        assertThat(result.get().text()).isEqualTo(EAN13);
        assertThat(result.get().format()).isEqualTo("EAN_13");
    }

    @Test
    void decode_rotated90_stillReads() throws Exception {
        byte[] rotated = rotate90(png(BarcodeFormat.EAN_13, EAN13, 400, 150));

        Optional<DecodedBarcode> result = decoder.decode(rotated);

        assertThat(result).isPresent();
        assertThat(result.get().text()).isEqualTo(EAN13);
    }

    @Test
    void decode_blankImage_empty() throws Exception {
        BufferedImage blank = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(blank, "PNG", out);

        assertThat(decoder.decode(out.toByteArray())).isEmpty();
    }

    private byte[] png(BarcodeFormat format, String text, int w, int h) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, format, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    /** 🔴 폭·높이를 맞바꿔 새 캔버스를 만든다 — 같은 크기에 그리면 잘린다. */
    private byte[] rotate90(byte[] png) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage dst = new BufferedImage(src.getHeight(), src.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.translate(src.getHeight(), 0);
        g.rotate(Math.PI / 2);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "PNG", out);
        return out.toByteArray();
    }
}
