package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Renderer unit tests with a mocked {@link FontRegistry} (returns a JDK logical base font) and a
 * mocked {@link ImageStorageService}. render() calls {@code fontRegistry.load} per text element, so
 * that stub is required only in tests that render text.
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailRendererTest {

    @Mock
    private FontRegistry fontRegistry;

    @Mock
    private ImageStorageService imageStorageService;

    // Real primitive (spy) so the delegated final draw actually reaches the mock Graphics2D — proves the
    // FEATURE_2608_08 delegation is pixel-identical (draw ordering/coords assertions stay valid).
    @Spy
    private ImageCompositeSupport imageCompositeSupport = new ImageCompositeSupport();

    @InjectMocks
    private ThumbnailRenderer renderer;

    @Test
    void render_producesNonEmptyJpeg() throws Exception {
        given(fontRegistry.load(any())).willReturn(new Font("SansSerif", Font.PLAIN, 12));

        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(200)
                .canvasHeight(200)
                .elements(List.of(imageElement("productImage"), textElement("productName")))
                .build();

        byte[] jpeg = renderer.render(
                template,
                Map.of("productName", "Test Product"),
                Map.of("productImage", pngBytes(80, 80)));

        assertThat(jpeg).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(200);
        assertThat(decoded.getHeight()).isEqualTo(200);
    }

    @Test
    void render_blankBinding_skipsTextElement() throws Exception {
        // No stub for fontRegistry.load: a blank binding must skip the text element before loading a font.
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(150)
                .canvasHeight(150)
                .elements(List.of(textElement("productName")))
                .build();

        byte[] jpeg = renderer.render(template, Map.of(), Map.of()); // productName absent → skipped

        assertThat(jpeg).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(150);
        assertThat(decoded.getHeight()).isEqualTo(150);
    }

    @Test
    void render_gradientAuto_withProductImage_paintsTopBottomColors() throws Exception {
        // Source: top half RED, bottom half BLUE → auto gradient RED(top)→BLUE(bottom).
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(100).canvasHeight(100)
                .backgroundMode(BackgroundMode.GRADIENT_AUTO)
                .elements(List.of())
                .build();

        byte[] jpeg = renderer.render(template, Map.of(),
                Map.of("productImage", topBottomPng(80, 80, Color.RED, Color.BLUE)));

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
        Color top = new Color(out.getRGB(50, 2));
        Color bottom = new Color(out.getRGB(50, 97));
        assertThat(top.getRed()).isGreaterThan(top.getBlue());     // top ~ red
        assertThat(bottom.getBlue()).isGreaterThan(bottom.getRed()); // bottom ~ blue
    }

    @Test
    void render_gradientAuto_withoutProductImage_fallsBackToGray() throws Exception {
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(100).canvasHeight(100)
                .backgroundMode(BackgroundMode.GRADIENT_AUTO)
                .elements(List.of())
                .build();

        byte[] jpeg = renderer.render(template, Map.of(), Map.of()); // no productImage binding

        Color px = new Color(ImageIO.read(new ByteArrayInputStream(jpeg)).getRGB(50, 50));
        assertThat(px.getRed()).isBetween(110, 145);
        assertThat(px.getGreen()).isBetween(110, 145);
        assertThat(px.getBlue()).isBetween(110, 145);
    }

    @Test
    void render_black_paintsBlackBackground() throws Exception {
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(80).canvasHeight(80)
                .backgroundMode(BackgroundMode.BLACK)
                .elements(List.of())
                .build();

        byte[] jpeg = renderer.render(template, Map.of(), Map.of());

        Color px = new Color(ImageIO.read(new ByteArrayInputStream(jpeg)).getRGB(40, 40));
        assertThat(px.getRed()).isLessThan(40);
        assertThat(px.getGreen()).isLessThan(40);
        assertThat(px.getBlue()).isLessThan(40);
    }

    @Test
    void render_largeLineSpacing_producesValidJpeg() throws Exception {
        given(fontRegistry.load(any())).willReturn(new Font("SansSerif", Font.PLAIN, 12));

        // Long multi-line text in a tall box with a big multiplier: exercises the spacing path end-to-end.
        TemplateElement spaced = TemplateElement.builder()
                .type("text")
                .bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(160).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(1L)
                .color("#000000")
                .maxFontSize(40)
                .minFontSize(10)
                .maxLines(2)
                .lineSpacing(1.6)
                .build();
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(200).canvasHeight(200)
                .elements(List.of(spaced))
                .build();

        byte[] jpeg = renderer.render(template, Map.of("productName", "Alpha Beta Gamma Delta"), Map.of());

        assertThat(jpeg).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(200);
        assertThat(decoded.getHeight()).isEqualTo(200);
    }

    @Test
    void drawElements_productImageIsBase_paintedBeforeOverlay_regardlessOfArrayOrder() throws Exception {
        // z-order contract: the productImage element is drawn FIRST (base), even when it is last in the
        // array; the fixed overlay is drawn AFTER. Verified by draw order on a mock Graphics2D (no pixels).
        int canvas = 200;
        TemplateElement overlay = fixedImageElement("overlay.png", 20, 20, 40, 40); // index 0
        TemplateElement base = productImageElement(0, 0, canvas, canvas);           // index 1 (last)

        given(imageStorageService.getBytes("overlay.png")).willReturn(pngBytes(80, 80));
        Map<String, byte[]> imageBindings = Map.of("productImage", pngBytes(80, 80));

        Graphics2D g = mock(Graphics2D.class);
        renderer.drawElements(g, List.of(overlay, base), Map.of(), imageBindings);

        InOrder order = inOrder(g);
        // base = full-canvas square fit → drawn at (0,0,200,200) FIRST
        order.verify(g).drawImage(any(), eq(0), eq(0), eq(canvas), eq(canvas), any());
        // overlay = 40x40 region square fit → drawn at (20,20,40,40) AFTER
        order.verify(g).drawImage(any(), eq(20), eq(20), eq(40), eq(40), any());
    }

    @Test
    void drawElement_border_drawsRectInsetByHalfWidth() throws Exception {
        // A bordered fixed-image element → drawRect around its region, inset by half the border width.
        TemplateElement bordered = TemplateElement.builder()
                .type("image").src("badge.png")
                .region(TemplateElement.Region.builder().x(10).y(10).w(50).h(50).build())
                .opacity(1.0)
                .borderColor("#000000").borderWidth(4)
                .build();
        given(imageStorageService.getBytes("badge.png")).willReturn(pngBytes(40, 40));

        Graphics2D g = mock(Graphics2D.class);
        renderer.drawElements(g, List.of(bordered), Map.of(), Map.of());

        // half = 4/2 = 2 → rect (12, 12, 50-4, 50-4)
        verify(g).drawRect(12, 12, 46, 46);
    }

    @Test
    void render_textOutline_paintsOutlineColorAroundGlyphs() throws Exception {
        given(fontRegistry.load(any())).willReturn(new Font("SansSerif", Font.BOLD, 12));

        TemplateElement outlined = TemplateElement.builder()
                .type("text").bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(1L).color("#000000")
                .maxFontSize(150).minFontSize(40).maxLines(1)
                .outlineColor("#FF0000").outlineWidth(6) // red outline
                .build();
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(200).canvasHeight(200)
                .backgroundMode(BackgroundMode.WHITE)
                .elements(List.of(outlined))
                .build();

        byte[] jpeg = renderer.render(template, Map.of("productName", "A"), Map.of());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));

        boolean hasRed = false;
        for (int y = 0; y < 200 && !hasRed; y++) {
            for (int x = 0; x < 200; x++) {
                Color px = new Color(out.getRGB(x, y));
                if (px.getRed() > 150 && px.getGreen() < 100 && px.getBlue() < 100) {
                    hasRed = true;
                    break;
                }
            }
        }
        assertThat(hasRed).isTrue(); // outline stroke painted in red around the black "A"
    }

    @Test
    void render_textGradient_paintsBothEndpointColors() throws Exception {
        given(fontRegistry.load(any())).willReturn(new Font("SansSerif", Font.BOLD, 12));

        TemplateElement grad = TemplateElement.builder()
                .type("text").bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(1L)
                .color("#FF0000")          // top = red
                .gradientColor("#0000FF")  // bottom = blue
                .maxFontSize(180).minFontSize(60).maxLines(1)
                .build();
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(200).canvasHeight(200)
                .backgroundMode(BackgroundMode.WHITE)
                .elements(List.of(grad))
                .build();

        byte[] jpeg = renderer.render(template, Map.of("productName", "M"), Map.of());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));

        boolean hasRed = false;
        boolean hasBlue = false;
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                Color px = new Color(out.getRGB(x, y));
                if (px.getRed() > 130 && px.getGreen() < 110 && px.getBlue() < 110) hasRed = true;
                if (px.getBlue() > 130 && px.getRed() < 110 && px.getGreen() < 110) hasBlue = true;
            }
        }
        assertThat(hasRed).isTrue();  // top of the glyph ~ red
        assertThat(hasBlue).isTrue(); // bottom of the glyph ~ blue
    }

    @Test
    void render_textGradient90deg_leftStartColor_rightEndColor() throws Exception {
        given(fontRegistry.load(any())).willReturn(new Font("SansSerif", Font.BOLD, 12));

        TemplateElement grad = TemplateElement.builder()
                .type("text").bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(1L)
                .color("#FF0000")          // start = red
                .gradientColor("#0000FF")  // end = blue
                .gradientAngle(90)         // 90° → left→right
                .maxFontSize(180).minFontSize(60).maxLines(1)
                .build();
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(200).canvasHeight(200)
                .backgroundMode(BackgroundMode.WHITE)
                .elements(List.of(grad))
                .build();

        byte[] jpeg = renderer.render(template, Map.of("productName", "M"), Map.of());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));

        boolean redOnLeft = false;
        boolean blueOnRight = false;
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                Color px = new Color(out.getRGB(x, y));
                if (x < 100 && px.getRed() > 130 && px.getGreen() < 110 && px.getBlue() < 110) redOnLeft = true;
                if (x > 100 && px.getBlue() > 130 && px.getRed() < 110 && px.getGreen() < 110) blueOnRight = true;
            }
        }
        assertThat(redOnLeft).isTrue();   // left = start = red
        assertThat(blueOnRight).isTrue(); // right = end = blue
    }

    @Test
    void drawImageElement_fixedSrc_loadedViaGetBytes() throws Exception {
        // Fixed image element (src set, bind null = asset reuse path) → bytes loaded via getBytes(src).
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .canvasWidth(100).canvasHeight(100)
                .elements(List.of(fixedImageElement("badge.png", 0, 0, 100, 100)))
                .build();
        given(imageStorageService.getBytes("badge.png")).willReturn(pngBytes(40, 40));

        byte[] jpeg = renderer.render(template, Map.of(), Map.of());

        assertThat(jpeg).isNotEmpty();
        verify(imageStorageService).getBytes("badge.png");
    }

    private TemplateElement productImageElement(int x, int y, int w, int h) {
        return TemplateElement.builder()
                .type("image")
                .bind("productImage")
                .region(TemplateElement.Region.builder().x(x).y(y).w(w).h(h).build())
                .opacity(1.0)
                .build();
    }

    private TemplateElement fixedImageElement(String src, int x, int y, int w, int h) {
        return TemplateElement.builder()
                .type("image")
                .src(src)
                .region(TemplateElement.Region.builder().x(x).y(y).w(w).h(h).build())
                .opacity(1.0)
                .build();
    }

    private byte[] topBottomPng(int w, int h, Color top, Color bottom) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(top);
        g.fillRect(0, 0, w, h / 2);
        g.setColor(bottom);
        g.fillRect(0, h / 2, w, h - h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private TemplateElement textElement(String bind) {
        return TemplateElement.builder()
                .type("text")
                .bind(bind)
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(60).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(1L)
                .color("#000000")
                .maxFontSize(40)
                .minFontSize(10)
                .maxLines(2)
                .build();
    }

    private TemplateElement imageElement(String bind) {
        return TemplateElement.builder()
                .type("image")
                .bind(bind)
                .region(TemplateElement.Region.builder().x(0).y(0).w(200).h(120).build())
                .opacity(1.0)
                .build();
    }

    private byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
