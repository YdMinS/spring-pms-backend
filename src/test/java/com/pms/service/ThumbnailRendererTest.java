package com.pms.service;

import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
