package com.pms.service;

import com.pms.domain.ImageOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Real-render pixel tests for the pipeline engine (FEATURE_2608_08). Base = solid white 200×200, overlay =
 * solid red 40×40 (real bytes returned by the mocked {@code getBytes}). Asserts anchor placement, opacity
 * blend, and the no-op paths (empty/null ops, unknown type). JPEG(0.9) is lossy, so pixels are sampled
 * well inside blocks with tolerant thresholds.
 */
@ExtendWith(MockitoExtension.class)
class ImageProcessorTest {

    @Mock private ImageStorageService imageStorageService;

    /** Build against the injected mock (constructed per test — @Mock is set before each test method). */
    private ImageProcessor build() {
        return new ImageProcessor(imageStorageService, new ImageCompositeSupport());
    }

    private static final int SIZE = 200;
    private static final String KEY = "red.png";

    @Test
    void overlay_bottomRightWithMargin_placesRedAtCorner_oppositeStaysWhite() throws Exception {
        given(imageStorageService.getBytes(KEY)).willReturn(solid(40, 40, Color.RED));

        // scale 20% of short side (200) → 40px long side; margin 5% → 10px. Red block x,y ∈ [150,190).
        ImageOp op = ImageOp.builder().type("overlay").assetStorageKey(KEY)
                .anchor("BOTTOM_RIGHT").scalePercent(20).marginPercent(5).build();

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        assertRed(out, 170, 170);      // inside the red block
        assertWhite(out, 20, 20);      // opposite (top-left) corner untouched
    }

    @Test
    void overlay_opacityHalf_blendsWithBase() throws Exception {
        given(imageStorageService.getBytes(KEY)).willReturn(solid(40, 40, Color.RED));

        // default anchor BOTTOM_RIGHT, margin 0 → red block ∈ [160,200). opacity 0.5 → white/red midpoint.
        ImageOp op = ImageOp.builder().type("overlay").assetStorageKey(KEY)
                .scalePercent(20).opacity(0.5).build();

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        Color c = new Color(out.getRGB(180, 180));
        assertThat(c.getRed()).isGreaterThan(220);          // white + red both high red
        assertThat(c.getGreen()).isBetween(90, 170);        // ~128 (blend)
        assertThat(c.getBlue()).isBetween(90, 170);         // ~128 (blend)
    }

    @Test
    void emptyOps_returnsBaseUnchanged() throws Exception {
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of()));

        assertThat(out.getWidth()).isEqualTo(SIZE);
        assertThat(out.getHeight()).isEqualTo(SIZE);
        assertWhite(out, 100, 100);
    }

    @Test
    void nullOps_returnsBaseUnchanged() throws Exception {
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), null));

        assertWhite(out, 100, 100);
    }

    @Test
    void unknownType_skipped_baseUnchanged() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").build(); // not implemented → skip
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        assertWhite(out, 100, 100);
    }

    // ---- helpers ----

    private static void assertRed(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y));
        assertThat(c.getRed()).as("red at %d,%d", x, y).isGreaterThan(200);
        assertThat(c.getGreen()).isLessThan(60);
        assertThat(c.getBlue()).isLessThan(60);
    }

    private static void assertWhite(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y));
        assertThat(c.getRed()).as("white at %d,%d", x, y).isGreaterThan(230);
        assertThat(c.getGreen()).isGreaterThan(230);
        assertThat(c.getBlue()).isGreaterThan(230);
    }

    private static byte[] solid(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
