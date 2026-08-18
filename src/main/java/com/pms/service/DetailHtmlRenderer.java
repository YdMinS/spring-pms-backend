package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Renders a {@link DetailTemplate} to a plain HTML string (FEATURE_2608_06 / Step 2-1) — the single
 * entry point for detail-page HTML. Do NOT assemble detail HTML anywhere else; go through this service.
 *
 * <p>Mirror of {@link ThumbnailRenderer} but flow-layout and pure: blocks are emitted top-to-bottom in
 * array order, there is no canvas / absolute positioning, and there is NO I/O — {@code imageZone}/{@code asset}
 * URLs are referenced verbatim (they are already public S3 URLs / storage keys). That makes this a pure,
 * dependency-free unit under test.</p>
 *
 * <p>Marketplaces strip external CSS and class attributes, so only inline styles are used (no flexbox /
 * grid). All bound text and asset {@code src} values are HTML-escaped to prevent injection / breakage.</p>
 */
@Service
public class DetailHtmlRenderer {

    /**
     * @param template      the detail template (block array). Null blocks → empty string.
     * @param textBindings  field key → value for {@code text} blocks (blank/absent → defaultValue fallback).
     * @param zoneImageUrls zoneId → ordered image URLs for {@code imageZone} blocks (empty → block skipped).
     * @return concatenated HTML for every rendered block.
     */
    public String render(DetailTemplate template, Map<String, String> textBindings,
                         Map<String, List<String>> zoneImageUrls) {
        if (template == null || template.getBlocks() == null) {
            return "";
        }
        Map<String, String> texts = textBindings != null ? textBindings : Map.of();
        Map<String, List<String>> zones = zoneImageUrls != null ? zoneImageUrls : Map.of();

        StringBuilder html = new StringBuilder();
        for (DetailBlock block : template.getBlocks()) {
            if (block == null || block.getType() == null) {
                continue;
            }
            switch (block.getType()) {
                case "text" -> renderText(html, block, texts);
                case "imageZone" -> renderImageZone(html, block, zones);
                case "asset" -> renderAsset(html, block);
                case "spacer" -> renderSpacer(html, block);
                default -> { /* unknown type → skip (lenient) */ }
            }
        }
        return html.toString();
    }

    /** text: bound value, else defaultValue fallback, else skip. Escaped and wrapped in a styled &lt;p&gt;. */
    private void renderText(StringBuilder html, DetailBlock block, Map<String, String> texts) {
        String value = block.getBind() != null ? texts.get(block.getBind()) : null;
        if (!StringUtils.hasText(value)) {
            value = block.getDefaultValue();
        }
        if (!StringUtils.hasText(value)) {
            return; // both blank → conditional-render skip (thumbnail convention)
        }
        html.append("<div style=\"").append(wrapperStyle(block)).append("\">")
                .append("<p style=\"width:").append(width(block)).append("%;")
                .append(TextStyleSupport.toCss(block.getTextStyle()))   // registered, valid style overrides only
                .append("\">")
                .append(escape(value))
                .append("</p></div>");
    }

    /** imageZone: each URL becomes an &lt;img&gt; in order. Empty/absent zone → skip. */
    private void renderImageZone(StringBuilder html, DetailBlock block, Map<String, List<String>> zones) {
        List<String> urls = block.getBind() != null ? zones.getOrDefault(block.getBind(), List.of()) : List.of();
        if (urls.isEmpty()) {
            return;
        }
        html.append("<div style=\"").append(wrapperStyle(block)).append("\">");
        for (String url : urls) {
            appendImg(html, url, block);
        }
        html.append("</div>");
    }

    /** asset: fixed library image via src (storage key). Blank src → skip. */
    private void renderAsset(StringBuilder html, DetailBlock block) {
        if (!StringUtils.hasText(block.getSrc())) {
            return;
        }
        html.append("<div style=\"").append(wrapperStyle(block)).append("\">");
        appendImg(html, block.getSrc(), block);
        html.append("</div>");
    }

    /** spacer: a vertical gap. heightPx null/≤0 → default 24, &gt;600 → clamped to 600. Always rendered. */
    private void renderSpacer(StringBuilder html, DetailBlock block) {
        Integer raw = block.getHeightPx();
        int h = (raw == null || raw <= 0) ? 24 : Math.min(raw, 600);
        html.append("<div style=\"height:").append(h).append("px;\"></div>");
    }

    private void appendImg(StringBuilder html, String url, DetailBlock block) {
        html.append("<img src=\"").append(escape(url))
                .append("\" style=\"display:inline-block;max-width:").append(width(block))
                .append("%;height:auto;\"/>");
    }

    /** Wrapper div carries the horizontal alignment (also centers inline images). */
    private String wrapperStyle(DetailBlock block) {
        return "text-align:" + align(block) + ";";
    }

    private int width(DetailBlock block) {
        return block.getWidthPercent() != null ? block.getWidthPercent() : 100;
    }

    private String align(DetailBlock block) {
        String a = block.getAlign();
        return ("center".equals(a) || "right".equals(a)) ? a : "left";
    }

    /** Escape the 5 HTML-significant characters. Applied to every text value and src (injection/breakage). */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
