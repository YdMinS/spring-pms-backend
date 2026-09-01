package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Seeds the shared system font(s) on startup (idempotent, keyed by family). {@link FontAsset} is not
 * {@code @TenantId}, so a {@code tenantId=null} system row inserts fine without any TenantContext —
 * this runs in a non-web startup context.
 *
 * <p>Ships pointing at {@code fonts/system-sans.ttf}; until a real OFL binary is added there (see
 * {@code resources/fonts/README.md}), {@link FontRegistry} resolves the family key to a JDK logical
 * font, so rendering works regardless.</p>
 *
 * <p>Also promotes the seeded row to a public web font (FEATURE_2608_06 / 105): the bundled binary is
 * uploaded once to shared storage and its public URL stored in {@code webUrl}, so detail-page HTML can
 * emit an {@code @font-face} the buyer's browser can download. Promotion is idempotent (a row that
 * already has a URL is left alone) and never blocks startup.</p>
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class SystemFontSeeder implements ApplicationRunner {

    /** Package-visible so {@link DefaultTemplateSeeder} binds text to the same system font family. */
    static final String SYSTEM_FAMILY = "SansSerif";
    private static final String SYSTEM_STORAGE_KEY = "fonts/system-sans.ttf";
    private static final String SYSTEM_WEB_STACK =
            "'Nanum Gothic','Malgun Gothic','Apple SD Gothic Neo',sans-serif";

    private final FontAssetRepository fontAssetRepository;
    private final ImageStorageService imageStorageService;

    @Override
    public void run(ApplicationArguments args) {
        FontAsset font = fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(SYSTEM_FAMILY)
                .orElseGet(() -> {
                    FontAsset seeded = fontAssetRepository.save(FontAsset.builder()
                            .tenantId(null) // system-shared
                            .displayName("System Sans")
                            .familyKey(SYSTEM_FAMILY)
                            .source(FontSource.BUNDLED)
                            .storageKey(SYSTEM_STORAGE_KEY)
                            .webStack(SYSTEM_WEB_STACK)
                            .build());
                    log.info("Seeded system font: {}", SYSTEM_FAMILY);
                    return seeded;
                });
        promoteToPublicUrl(font);
    }

    /**
     * Upload the bundled binary to shared storage once and remember its public URL. No-op when the row
     * already has one, when the classpath binary is missing, or when the storage backend returns a disk
     * path (local profile) rather than a public URL — the CSS fallback stack still applies in that case.
     *
     * <p>⚠️ Never let a storage failure stop the application from booting, and never touch
     * {@code storageKey}: {@link FontRegistry} loads BUNDLED fonts from the classpath through it, so an
     * S3 URL there would fall back to a JDK logical font and break CJK thumbnail rendering.</p>
     */
    private void promoteToPublicUrl(FontAsset font) {
        if (font.getWebUrl() != null && !font.getWebUrl().isBlank()) {
            return; // already promoted
        }
        try {
            ClassPathResource resource = new ClassPathResource(SYSTEM_STORAGE_KEY);
            if (!resource.exists()) {
                log.info("System font binary not bundled ({}); detail pages use the CSS fallback stack",
                        SYSTEM_STORAGE_KEY);
                return;
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            String stored = imageStorageService.uploadShared(bytes, "fonts", "system-sans.ttf", "font/ttf");
            if (stored == null || !stored.startsWith("http")) {
                log.info("Shared font storage returned a non-public value; keeping fallback stack only");
                return;
            }
            fontAssetRepository.save(font.toBuilder().webUrl(stored).build()); // id present → UPDATE
            log.info("Promoted system font to public URL: {}", stored);
        } catch (Exception e) {
            log.warn("Failed to promote system font to a public URL (detail pages fall back to the CSS stack)", e);
        }
    }
}
