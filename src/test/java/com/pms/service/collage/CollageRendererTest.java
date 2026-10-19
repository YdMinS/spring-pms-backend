package com.pms.service.collage;

import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import com.pms.domain.CollageShape;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collage pixels (FEATURE_2610_21 D18 · D19 · D44 · D45 · D50): photos clipped to their cells, the gap in the gap
 * colour, a missing photo leaves its cell transparent (template on) or white (template off).
 */
class CollageRendererTest {

    private final CollageRenderer renderer = new CollageRenderer();

    private static BufferedImage solid(Color color) {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        return img;
    }

    private static CollageLayout row(Boolean useTemplate) {
        return new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.05, "#00FF00",
                useTemplate, List.of(new CollageCell(1L, 1.0, 0.5, 0.5), new CollageCell(2L, 1.0, 0.5, 0.5)));
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void drawPng_photosFillCellsAndGapUsesGapColour() throws Exception {
        BufferedImage out = decode(renderer.drawPng(row(Boolean.TRUE),
                Map.of(1L, solid(Color.RED), 2L, solid(Color.BLUE)), 200, 100));

        assertThat(out.getRGB(10, 50)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(190, 50)).isEqualTo(Color.BLUE.getRGB());
        assertThat(out.getRGB(100, 50)).isEqualTo(Color.GREEN.getRGB());
    }

    @Test
    void drawPng_missingPhoto_transparentWithTemplate_whiteWithout() throws Exception {
        BufferedImage withTemplate = decode(renderer.drawPng(row(Boolean.TRUE),
                Map.of(1L, solid(Color.RED)), 200, 100));
        BufferedImage withoutTemplate = decode(renderer.drawPng(row(Boolean.FALSE),
                Map.of(1L, solid(Color.RED)), 200, 100));

        assertThat(withTemplate.getRGB(190, 50) >>> 24).isZero();
        assertThat(withoutTemplate.getRGB(190, 50)).isEqualTo(Color.WHITE.getRGB());
    }
}
