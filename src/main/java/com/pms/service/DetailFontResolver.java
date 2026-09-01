package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.FontAsset;
import com.pms.repository.FontAssetRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the {@link FontAsset} ids referenced by a template's text blocks into render-ready CSS values
 * (FEATURE_2608_06 / 105). Keeps {@link DetailHtmlRenderer} pure: the DB lookup ends here, exactly like
 * {@code textBindings} / {@code zoneImageUrls} being resolved by the caller.
 *
 * <p>⚠️ Tenant isolation: ids are looked up through {@code findSystemAndTenant} only, so a template
 * referencing another tenant's font id resolves to nothing (dropped, no leak).</p>
 *
 * <p>⚠️ Injection defence: the CSS family name is always our own {@code oclyx-font-{id}} — never the
 * user-supplied {@code displayName} — and both the {@code @font-face} URL and the admin-entered fallback
 * stack are dropped outright when they contain characters that could break out of the inline CSS.</p>
 */
@Service
@RequiredArgsConstructor
public class DetailFontResolver {

    private static final String STYLE_KEY = "fontFamily";
    private static final String FAMILY_PREFIX = "oclyx-font-";
    private static final String DEFAULT_STACK = "sans-serif";

    private final FontAssetRepository fontAssetRepository;

    /**
     * Kill switch: set {@code detail.font-face.enabled=false} to stop emitting {@code @font-face} (fonts
     * are no longer downloaded; the fallback stack still applies). Exists so a marketplace rejecting the
     * {@code <style>} block can be worked around by an env var instead of a redeploy.
     */
    @Value("${detail.font-face.enabled:true}")
    private boolean fontFaceEnabled;

    /**
     * @param blocks the template's blocks (null-tolerant); only {@code textStyle.fontFamily} is read.
     * @return key = the raw textStyle value ({@code "12"}), value = the resolved font.
     *         Unresolvable references are absent (the block then inherits the surrounding font).
     */
    public Map<String, DetailFont> resolve(List<DetailBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> idsByRawValue = collectFontIds(blocks);
        if (idsByRawValue.isEmpty()) {
            return Map.of();
        }
        Set<Long> wanted = new HashSet<>(idsByRawValue.values());
        Map<Long, FontAsset> visible = new HashMap<>();
        for (FontAsset font : fontAssetRepository.findSystemAndTenant(TenantContext.get())) {
            if (wanted.contains(font.getId())) {
                visible.put(font.getId(), font);
            }
        }

        Map<String, DetailFont> resolved = new HashMap<>();
        for (Map.Entry<String, Long> e : idsByRawValue.entrySet()) {
            FontAsset font = visible.get(e.getValue()); // not visible to this tenant → dropped
            if (font == null) {
                continue;
            }
            DetailFont detailFont = toDetailFont(font);
            if (detailFont != null) {
                resolved.put(e.getKey(), detailFont);
            }
        }
        return resolved;
    }

    /** Raw textStyle value → font id, keeping only values that parse as an integer ("gothic" is ignored). */
    private Map<String, Long> collectFontIds(List<DetailBlock> blocks) {
        Map<String, Long> ids = new HashMap<>();
        for (DetailBlock block : blocks) {
            if (block == null || block.getTextStyle() == null) {
                continue;
            }
            String raw = block.getTextStyle().get(STYLE_KEY);
            if (raw == null || ids.containsKey(raw)) {
                continue;
            }
            try {
                ids.put(raw, Long.parseLong(raw.trim()));
            } catch (NumberFormatException ex) {
                // not a font id → ignore (the renderer then inherits the surrounding font)
            }
        }
        return ids;
    }

    /** null = nothing usable (neither a downloadable binary nor a fallback stack) → no entry at all. */
    private DetailFont toDetailFont(FontAsset font) {
        String srcUrl = fontFaceEnabled ? safeSrcUrl(font.publicWebUrl()) : null;
        String stack = safeStack(font.getWebStack());
        if (srcUrl != null) {
            String family = "'" + FAMILY_PREFIX + font.getId() + "',"
                    + (stack != null ? stack : DEFAULT_STACK);
            return new DetailFont(font.getId(), family, srcUrl, format(font.publicWebUrl()));
        }
        if (stack != null) {
            return new DetailFont(font.getId(), stack, null, null);
        }
        return null;
    }

    /** Only https URLs with no character that could terminate the CSS url()/@font-face block. */
    private String safeSrcUrl(String url) {
        if (url == null || !url.startsWith("https://")) {
            return null;
        }
        for (char c : url.toCharArray()) {
            if (c == '\'' || c == '"' || c == '(' || c == ')' || c == ';' || Character.isWhitespace(c)) {
                return null;
            }
        }
        return url;
    }

    /** Admin-entered, but goes out as inline CSS → drop anything that could escape the declaration. */
    private String safeStack(String stack) {
        if (stack == null || stack.isBlank()) {
            return null;
        }
        if (stack.indexOf(';') >= 0 || stack.indexOf('"') >= 0
                || stack.indexOf('<') >= 0 || stack.indexOf('>') >= 0) {
            return null;
        }
        return stack;
    }

    private String format(String url) {
        return url.toLowerCase().endsWith(".otf") ? "opentype" : "truetype";
    }
}
