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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;

/**
 * Real-render pixel tests for the pipeline engine (FEATURE_2608_08). Base = solid white 200×200, overlay =
 * solid red 40×40 (real bytes returned by the mocked {@code getBytes}). Asserts anchor placement, opacity
 * blend, the no-op paths (empty/null ops, unknown type) and the {@code colorAdjust} 2-phase contract
 * (FEATURE_2609_35: base-only, order-independent, first op only). JPEG(0.9) is lossy, so pixels are
 * sampled well inside blocks with tolerant thresholds.
 */
@ExtendWith(MockitoExtension.class)
class ImageProcessorTest {

    @Mock private ImageStorageService imageStorageService;

    /** Build against the injected mock (constructed per test — @Mock is set before each test method). */
    private ImageProcessor build() {
        return new ImageProcessor(imageStorageService, new ImageCompositeSupport(), new ImageColorAdjustSupport());
    }

    private static final int SIZE = 200;
    private static final String KEY = "red.png";
    private static final Color MID_GREY = new Color(128, 128, 128);
    private static final Color COLORED = new Color(200, 100, 50);

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
    void overlay_topCenter_placesRedAtTopMiddle_cornersStayWhite() throws Exception {
        given(imageStorageService.getBytes(KEY)).willReturn(solid(40, 40, Color.RED));

        // scale 20% → 40px. TOP_CENTER, margin 0 → x ∈ [80,120), y ∈ [0,40).
        ImageOp op = ImageOp.builder().type("overlay").assetStorageKey(KEY)
                .anchor("TOP_CENTER").scalePercent(20).build();

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        assertRed(out, 100, 20);       // top-middle block
        assertWhite(out, 20, 20);      // top-left corner untouched
        assertWhite(out, 180, 20);     // top-right corner untouched
    }

    @Test
    void overlay_centerLeft_placesRedAtMiddleLeft_oppositeStaysWhite() throws Exception {
        given(imageStorageService.getBytes(KEY)).willReturn(solid(40, 40, Color.RED));

        // CENTER_LEFT, margin 0 → x ∈ [0,40), y ∈ [80,120).
        ImageOp op = ImageOp.builder().type("overlay").assetStorageKey(KEY)
                .anchor("CENTER_LEFT").scalePercent(20).build();

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        assertRed(out, 20, 100);       // middle-left block
        assertWhite(out, 180, 100);    // middle-right untouched
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
        ImageOp op = ImageOp.builder().type("resize").build(); // no such op type → skip
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(op)));

        assertWhite(out, 100, 100);
    }

    // ---- colorAdjust (FEATURE_2609_35) ----

    @Test
    void colorAdjust_brightnessUp_scalesPixelsByGain() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").brightness(50).build();

        // 128 × (1 + 50/100) = 192, no clipping.
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, MID_GREY), List.of(op)));

        assertChannels(out, 100, 100, 192, 192, 192);
    }

    @Test
    void colorAdjust_saturationMinus100_turnsGreyscale() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").saturation(-100).build();

        // Rec.709 luminance of pure red = 0.2126 × 255 ≈ 54, identical on all three channels.
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.RED), List.of(op)));

        assertChannels(out, 100, 100, 54, 54, 54);
    }

    @Test
    void colorAdjust_temperatureUp_warmsRedAndBlueOnly() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").temperature(100).build();

        // ±20% gain on R/B, green untouched: 128×1.2 ≈ 154, 128, 128×0.8 ≈ 102.
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, MID_GREY), List.of(op)));

        assertChannels(out, 100, 100, 154, 128, 102);
    }

    @Test
    void colorAdjust_allParamsNull_leavesPixelsUntouched() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").build(); // every param null → pass skipped

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, COLORED), List.of(op)));

        assertChannels(out, 100, 100, COLORED.getRed(), COLORED.getGreen(), COLORED.getBlue());
    }

    @Test
    void colorAdjust_outOfRangeBrightness_clampsToWhite() throws Exception {
        ImageOp op = ImageOp.builder().type("colorAdjust").brightness(9999).build();

        // Clamped to +100 → gain 2 → 128×2 saturates at 255 (no exception).
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, MID_GREY), List.of(op)));

        assertChannels(out, 100, 100, 255, 255, 255);
    }

    @Test
    void colorAdjust_afterOverlayInList_stillOnlyAffectsBase() throws Exception {
        given(imageStorageService.getBytes(KEY)).willReturn(solid(40, 40, Color.RED));

        // colorAdjust sits AFTER the overlay in the list, yet it must run on the base only.
        ImageOp overlay = ImageOp.builder().type("overlay").assetStorageKey(KEY).scalePercent(20).build();
        ImageOp adjust = ImageOp.builder().type("colorAdjust").saturation(-100).build();

        BufferedImage out = decode(build().process(solid(SIZE, SIZE, Color.WHITE), List.of(overlay, adjust)));

        assertRed(out, 180, 180);      // overlay keeps its color (never desaturated)
        assertWhite(out, 20, 20);      // white base stays white under greyscale
    }

    @Test
    void colorAdjust_twoOps_appliesOnlyTheFirst() throws Exception {
        ImageOp first = ImageOp.builder().type("colorAdjust").brightness(50).build();
        ImageOp second = ImageOp.builder().type("colorAdjust").brightness(50).build();

        // Applied once → 192. Accumulating would clip to 255 (128 × 1.5 × 1.5 = 288).
        BufferedImage out = decode(build().process(solid(SIZE, SIZE, MID_GREY), List.of(first, second)));

        assertChannels(out, 100, 100, 192, 192, 192);
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

    /** Channel compare tolerating JPEG(quality 0.9) re-encoding error. Delta 6 is enough on flat blocks. */
    private static void assertChannels(BufferedImage img, int x, int y, int r, int g, int b) {
        Color c = new Color(img.getRGB(x, y));
        assertThat(c.getRed()).as("R at %d,%d", x, y).isCloseTo(r, within(6));
        assertThat(c.getGreen()).as("G at %d,%d", x, y).isCloseTo(g, within(6));
        assertThat(c.getBlue()).as("B at %d,%d", x, y).isCloseTo(b, within(6));
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
