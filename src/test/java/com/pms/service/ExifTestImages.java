package com.pms.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Test fixture (FEATURE_2609_81): a JPEG carrying an EXIF Orientation tag, built without any library.
 * The stored image is {@code w×h} with a red LEFT half and a blue RIGHT half, so the display direction
 * after the orientation fix is visible from two pixel samples.
 */
final class ExifTestImages {

    private ExifTestImages() {
    }

    /** Red left half / blue right half, JPEG-encoded, with an APP1 Exif segment holding {@code orientation}. */
    static byte[] leftRedRightBlueJpeg(int w, int h, int orientation) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return withOrientation(out.toByteArray(), orientation);
    }

    /** Insert an APP1 Exif segment (big-endian TIFF, IFD0 with one SHORT tag 0x0112) right after APP0. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,                 // APP1, length 34
                'E', 'x', 'i', 'f', 0x00, 0x00,                        // Exif header
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,          // TIFF header, IFD0 at offset 8
                0x00, 0x01,                                            // 1 entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,        // tag 0x0112, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,                  // value
                0x00, 0x00, 0x00, 0x00                                 // no next IFD
        };
        // ImageIO writes SOI (2 bytes) then APP0 JFIF: FF E0 + 2-byte length (length includes itself).
        int app0Len = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int insertAt = 4 + app0Len;
        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, insertAt);
        System.arraycopy(app1, 0, result, insertAt, app1.length);
        System.arraycopy(jpeg, insertAt, result, insertAt + app1.length, jpeg.length - insertAt);
        return result;
    }

    static boolean isRed(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y));
        return c.getRed() > 180 && c.getBlue() < 80;
    }

    static boolean isBlue(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y));
        return c.getBlue() > 180 && c.getRed() < 80;
    }
}
