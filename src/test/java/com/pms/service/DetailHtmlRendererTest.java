package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure renderer — no Spring, no I/O. Covers block kinds, blank/fallback skip rules, ordering, and
 * HTML escaping (injection guard = MUST-KEEP).
 */
class DetailHtmlRendererTest {

    private final DetailHtmlRenderer renderer = new DetailHtmlRenderer();

    private DetailTemplate template(DetailBlock... blocks) {
        return DetailTemplate.builder().name("t").blocks(List.of(blocks)).active(true).isDefault(true).build();
    }

    @Test
    void text_boundValue_rendersWithWidthAndAlign() {
        DetailBlock block = DetailBlock.builder()
                .type("text").bind("brandName").widthPercent(80).align("center").build();
        String html = renderer.render(template(block), Map.of("brandName", "행복상회"), Map.of());

        assertThat(html).contains("행복상회");
        assertThat(html).contains("text-align:center;");
        assertThat(html).contains("width:80%;");
    }

    @Test
    void text_blankBinding_withDefaultValue_rendersFallback() {
        DetailBlock block = DetailBlock.builder()
                .type("text").bind("freeShipping").defaultValue("무료배송").build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).contains("무료배송");
    }

    @Test
    void text_blankBindingAndDefault_isSkipped() {
        DetailBlock block = DetailBlock.builder().type("text").bind("brandName").build();
        String html = renderer.render(template(block), Map.of("brandName", "  "), Map.of());

        assertThat(html).isEmpty();
    }

    @Test
    void imageZone_rendersImgsInOrder() {
        DetailBlock block = DetailBlock.builder().type("imageZone").bind("product_photos").build();
        String html = renderer.render(template(block), Map.of(),
                Map.of("product_photos", List.of("https://s3/a.jpg", "https://s3/b.jpg", "https://s3/c.jpg")));

        assertThat(html.indexOf("a.jpg")).isLessThan(html.indexOf("b.jpg"));
        assertThat(html.indexOf("b.jpg")).isLessThan(html.indexOf("c.jpg"));
        assertThat(html.split("<img", -1).length - 1).isEqualTo(3);
    }

    @Test
    void imageZone_empty_isSkipped() {
        DetailBlock block = DetailBlock.builder().type("imageZone").bind("detail_photos").build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).isEmpty();
    }

    @Test
    void asset_rendersSingleImg() {
        DetailBlock block = DetailBlock.builder().type("asset").src("tenants/1/thumbnail-assets/notice.png").build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).contains("<img src=\"tenants/1/thumbnail-assets/notice.png\"");
        assertThat(html.split("<img", -1).length - 1).isEqualTo(1);
    }

    @Test
    void text_value_isHtmlEscaped() {
        DetailBlock block = DetailBlock.builder().type("text").bind("brandName").build();
        String html = renderer.render(template(block), Map.of("brandName", "<b>x</b>"), Map.of());

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;");
        assertThat(html).doesNotContain("<b>x</b>");
    }
}
