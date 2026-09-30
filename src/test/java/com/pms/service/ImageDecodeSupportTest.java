package com.pms.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static com.pms.service.ExifTestImages.isBlue;
import static com.pms.service.ExifTestImages.isRed;
import static com.pms.service.ExifTestImages.leftRedRightBlueJpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEATURE_2609_81: EXIF Orientation is applied at decode time. Stored image = 40×20, red left half /
 * blue right half. Pixels are sampled well inside each half (JPEG is lossy).
 */
class ImageDecodeSupportTest {

    private final ImageDecodeSupport support = new ImageDecodeSupport();

    @Test
    void orientation6_rotatesClockwise_redOnTop() throws Exception {
        BufferedImage img = support.decode(leftRedRightBlueJpeg(40, 20, 6), "test");

        assertThat(img.getWidth()).isEqualTo(20);
        assertThat(img.getHeight()).isEqualTo(40);
        assertThat(isRed(img, 10, 5)).as("top = stored left").isTrue();
        assertThat(isBlue(img, 10, 35)).as("bottom = stored right").isTrue();
    }

    @Test
    void orientation8_rotatesCounterClockwise_blueOnTop() throws Exception {
        BufferedImage img = support.decode(leftRedRightBlueJpeg(40, 20, 8), "test");

        assertThat(img.getWidth()).isEqualTo(20);
        assertThat(img.getHeight()).isEqualTo(40);
        assertThat(isBlue(img, 10, 5)).as("top = stored right").isTrue();
        assertThat(isRed(img, 10, 35)).as("bottom = stored left").isTrue();
    }

    @Test
    void orientation3_rotates180_keepsSize_swapsHalves() throws Exception {
        BufferedImage img = support.decode(leftRedRightBlueJpeg(40, 20, 3), "test");

        assertThat(img.getWidth()).isEqualTo(40);
        assertThat(img.getHeight()).isEqualTo(20);
        assertThat(isBlue(img, 5, 10)).isTrue();
        assertThat(isRed(img, 35, 10)).isTrue();
    }

    @Test
    void noExif_returnsPixelsAsDecoded() throws Exception {
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(leftRedRightBlueJpeg(40, 20, 1)));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(stored, "png", png);

        BufferedImage img = support.decode(png.toByteArray(), "test");

        assertThat(img.getWidth()).isEqualTo(40);
        assertThat(img.getHeight()).isEqualTo(20);
        assertThat(isRed(img, 5, 10)).isTrue();
        assertThat(isBlue(img, 35, 10)).isTrue();
    }

    @Test
    void undecodableBytes_throwIllegalArgument() {
        assertThatThrownBy(() -> support.decode(new byte[]{1, 2, 3}, "base image"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base image");
    }

    @Test
    void flattenOnWhite_transparentPng_becomesWhite_keepsOpaquePixels() throws Exception {
        // FEATURE_2609_81 D21·D22: left half transparent, right half opaque red.
        BufferedImage argb = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(10, 0, 10, 20);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", png);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(support.flattenOnWhite(png.toByteArray())));

        assertThat(out.getColorModel().hasAlpha()).isFalse();
        assertThat(out.getRGB(5, 10) & 0xFFFFFF).as("transparent → white").isEqualTo(0xFFFFFF);
        assertThat(isRed(out, 15, 10)).as("opaque pixels unchanged").isTrue();
    }

    @Test
    void flattenOnWhite_noAlpha_returnsInputBytes() throws Exception {
        byte[] jpeg = leftRedRightBlueJpeg(40, 20, 1);

        assertThat(support.flattenOnWhite(jpeg)).isSameAs(jpeg);
    }
}
