package com.pms.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure sampler unit tests (no Graphics/render). A top-half-RED / bottom-half-BLUE image must sample to
 * exactly RED and BLUE, proving the {@code [0,h/2)} vs {@code [h/2,h)} split (row h/2 belongs to bottom).
 */
class ImageColorSamplerTest {

    @Test
    void topBottom_splitsAtMidRow() {
        BufferedImage img = topBottomImage(100, 100, Color.RED, Color.BLUE);

        Color[] tb = ImageColorSampler.topBottom(img);

        assertThat(tb[0]).isEqualTo(Color.RED);   // top half [0,50)
        assertThat(tb[1]).isEqualTo(Color.BLUE);  // bottom half [50,100)
    }

    @Test
    void averageColor_emptyRange_returnsGray() {
        BufferedImage img = topBottomImage(20, 20, Color.RED, Color.BLUE);

        assertThat(ImageColorSampler.averageColor(img, 10, 10)).isEqualTo(Color.GRAY);
    }

    private BufferedImage topBottomImage(int w, int h, Color top, Color bottom) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(top);
        g.fillRect(0, 0, w, h / 2);
        g.setColor(bottom);
        g.fillRect(0, h / 2, w, h - h / 2);
        g.dispose();
        return img;
    }
}
