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
    void spacer_rendersGapDiv() {
        DetailBlock block = DetailBlock.builder().type("spacer").heightPx(24).build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).isEqualTo("<div style=\"height:24px;\"></div>");
    }

    @Test
    void spacer_nullHeight_usesDefault24() {
        DetailBlock block = DetailBlock.builder().type("spacer").build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).isEqualTo("<div style=\"height:24px;\"></div>");
    }

    @Test
    void spacer_oversizeHeight_clampedTo600() {
        DetailBlock block = DetailBlock.builder().type("spacer").heightPx(1000).build();
        String html = renderer.render(template(block), Map.of(), Map.of());

        assertThat(html).isEqualTo("<div style=\"height:600px;\"></div>");
    }

    @Test
    void text_value_isHtmlEscaped() {
        DetailBlock block = DetailBlock.builder().type("text").bind("brandName").build();
        String html = renderer.render(template(block), Map.of("brandName", "<b>x</b>"), Map.of());

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;");
        assertThat(html).doesNotContain("<b>x</b>");
    }

    // --- text style (registry) — FEATURE_2608_06 / 19. Map ordering is nondeterministic → contains() only.

    @Test
    void text_fontSize_rendersPx() {
        String html = renderer.render(template(styledText(Map.of("fontSize", "18"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).contains("font-size:18px;");
    }

    @Test
    void text_color_rendersHex() {
        String html = renderer.render(template(styledText(Map.of("color", "#ff0000"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).contains("color:#ff0000;");
    }

    @Test
    void text_boldAndItalic_rendersWeightAndStyle() {
        String html = renderer.render(template(styledText(Map.of("bold", "true", "italic", "true"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).contains("font-weight:700;");
        assertThat(html).contains("font-style:italic;");
    }

    @Test
    void text_colorInjection_isDropped() { // MUST-KEEP security regression
        String html = renderer.render(template(styledText(Map.of("color", "red;font-size:99px"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).doesNotContain("color:red");
        assertThat(html).doesNotContain("font-size:99px");
    }

    @Test
    void text_fontSizeSuffixInjection_isDropped() { // MUST-KEEP: "18px;color:red" → parseInt fail → drop
        String html = renderer.render(template(styledText(Map.of("fontSize", "18px;color:red"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).doesNotContain("font-size:");
        assertThat(html).doesNotContain("color:red");
    }

    @Test
    void text_unknownKey_isIgnored() {
        String html = renderer.render(template(styledText(Map.of("foo", "bar"))),
                Map.of("brandName", "x"), Map.of());
        assertThat(html).doesNotContain("foo");
        assertThat(html).doesNotContain("bar");
    }

    @Test
    void text_nullStyleValue_isSkippedWithoutNpe() {
        Map<String, String> style = new java.util.HashMap<>();
        style.put("color", null);
        String html = renderer.render(template(styledText(style)), Map.of("brandName", "x"), Map.of());
        assertThat(html).doesNotContain("color:");
    }

    @Test
    void text_nullStyle_rendersSameAsBefore() {
        DetailBlock block = DetailBlock.builder().type("text").bind("brandName").build();
        String html = renderer.render(template(block), Map.of("brandName", "x"), Map.of());
        assertThat(html).contains("<p style=\"width:100%;\">");
    }

    private DetailBlock styledText(Map<String, String> style) {
        return DetailBlock.builder().type("text").bind("brandName").textStyle(style).build();
    }
}
