package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches base {@link Font}s for the thumbnail renderer, and lists the fonts available to the
 * current tenant (system ∪ tenant).
 *
 * <p>A base font is loaded once per {@code fontId} (size 12) and cached; {@link ThumbnailRenderer}
 * derives per-element sizes via {@code deriveFont}. BUNDLED fonts load from the classpath; if no binary
 * is present (none is shipped yet — see {@code resources/fonts/README.md}), the family key is resolved
 * to a JDK logical font so rendering still works. UPLOADED fonts load their bytes back through
 * {@link ImageStorageService#getBytes}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FontRegistry {

    private final FontAssetRepository fontAssetRepository;
    private final ImageStorageService imageStorageService;

    private final ConcurrentHashMap<Long, Font> cache = new ConcurrentHashMap<>();

    /** Cached base font (size 12) for a {@link FontAsset} id. Throws if the id is unknown. */
    public Font load(Long fontId) {
        if (fontId == null) {
            throw new IllegalArgumentException("fontId is required for text elements");
        }
        return cache.computeIfAbsent(fontId, id -> {
            FontAsset asset = fontAssetRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Font not found: " + id));
            return loadFont(asset);
        });
    }

    /** Fonts selectable in the editor: system (shared) ∪ current tenant. */
    public List<FontAsset> listAvailable() {
        return fontAssetRepository.findSystemAndTenant(TenantContext.get());
    }

    private Font loadFont(FontAsset asset) {
        try {
            if (asset.getSource() == FontSource.BUNDLED) {
                ClassPathResource resource = new ClassPathResource(asset.getStorageKey());
                if (resource.exists()) {
                    try (InputStream in = resource.getInputStream()) {
                        return Font.createFont(Font.TRUETYPE_FONT, in);
                    }
                }
                // No bundled binary → JDK logical font by family key (keeps rendering functional).
                log.debug("Bundled font resource missing ({}), falling back to logical font {}",
                        asset.getStorageKey(), asset.getFamilyKey());
                return new Font(asset.getFamilyKey(), Font.PLAIN, 12);
            }
            byte[] bytes = imageStorageService.getBytes(asset.getStorageKey());
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to load font " + asset.getId() + ": " + e.getMessage(), e);
        }
    }
}
