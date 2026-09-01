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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Seeds the shared system fonts on startup (idempotent, keyed by family). {@link FontAsset} is not
 * {@code @TenantId}, so {@code tenantId=null} system rows insert fine without any TenantContext —
 * this runs in a non-web startup context.
 *
 * <p>Each font is also promoted to a public web font (FEATURE_2608_06 / 105): the bundled binary is
 * uploaded once to shared storage and its public URL stored in {@code webUrl}, so detail-page HTML can
 * emit an {@code @font-face} the buyer's browser can download. Promotion is idempotent (a row that
 * already has a URL is left alone) and never blocks startup.</p>
 *
 * <p>To add a font: drop an OFL (redistributable) TTF into {@code resources/fonts/}, commit its license
 * under {@code resources/fonts/licenses/}, and add one {@link SystemFont} entry below — see
 * {@code resources/fonts/README.md}.</p>
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class SystemFontSeeder implements ApplicationRunner {

    /** Package-visible so {@link DefaultTemplateSeeder} binds text to the same system font family. */
    static final String SYSTEM_FAMILY = "SansSerif";

    /**
     * One bundled system font.
     *
     * <p>{@code classpathKey} doubles as the {@link FontAsset#getStorageKey()}: {@link FontRegistry}
     * loads BUNDLED binaries from the classpath through it. {@code webStack} is the CSS fallback used
     * when the browser cannot download the binary — it must not contain {@code ; " < >}, or
     * {@code DetailFontResolver} drops it.</p>
     */
    record SystemFont(String familyKey, String displayName, String classpathKey, String webStack) {}

    /** Package-visible so the test can assert every declared binary is actually bundled. */
    static final List<SystemFont> SYSTEM_FONTS = List.of(
            new SystemFont(SYSTEM_FAMILY, "System Sans", "fonts/system-sans.ttf",
                    "'Nanum Gothic','Malgun Gothic','Apple SD Gothic Neo',sans-serif"),
            new SystemFont("Pretendard", "Pretendard", "fonts/pretendard.ttf",
                    "'Pretendard','Apple SD Gothic Neo','Malgun Gothic',sans-serif"),
            new SystemFont("NanumMyeongjo", "나눔명조", "fonts/nanum-myeongjo.ttf",
                    "'Nanum Myeongjo','Batang',serif"),
            // ⚠️ Display faces: these three cover only ~2,400-2,600 of the 11,172 modern Hangul
            // syllables, so an uncommon syllable renders as tofu (□). Fine as a deliberate title font,
            // never as the default template font — that stays SYSTEM_FAMILY (full coverage).
            new SystemFont("BlackHanSans", "검은고딕", "fonts/black-han-sans.ttf",
                    "'Black Han Sans',sans-serif"),
            new SystemFont("Jua", "주아", "fonts/jua.ttf", "'Jua',sans-serif"),
            new SystemFont("DoHyeon", "도현", "fonts/do-hyeon.ttf", "'Do Hyeon',sans-serif"));

    private final FontAssetRepository fontAssetRepository;
    private final ImageStorageService imageStorageService;

    @Override
    public void run(ApplicationArguments args) {
        for (SystemFont def : SYSTEM_FONTS) {
            // Per-font isolation: one bad font must not cost the others, and never the boot.
            try {
                seedOne(def);
            } catch (Exception e) {
                log.warn("System font seed failed: {} ({})", def.familyKey(), e.toString());
            }
        }
    }

    /**
     * Insert the row if missing, then bring it up to date: fill a blank {@code webStack} and promote the
     * binary to a public URL. Writes at most once per concern, and only when something actually changed.
     *
     * <p>⚠️ Never touches {@code storageKey}: {@link FontRegistry} loads BUNDLED fonts from the classpath
     * through it, so an S3 URL there would fall back to a JDK logical font and break CJK thumbnails.</p>
     */
    private void seedOne(SystemFont def) {
        ClassPathResource resource = new ClassPathResource(def.classpathKey());
        if (!resource.exists()) {
            // Declared but not bundled: seeding it anyway would surface a dropdown entry that silently
            // renders in a different face (logical font for thumbnails, fallback stack for detail pages).
            log.info("System font binary not bundled ({}), skipping seed", def.classpathKey());
            return;
        }

        FontAsset row = fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(def.familyKey())
                .orElseGet(() -> {
                    FontAsset saved = fontAssetRepository.save(FontAsset.builder()
                            .tenantId(null) // system-shared
                            .displayName(def.displayName())
                            .familyKey(def.familyKey())
                            .source(FontSource.BUNDLED)
                            .storageKey(def.classpathKey())
                            .webStack(def.webStack())
                            .build());
                    log.info("Seeded system font: {}", def.familyKey());
                    return saved;
                });

        FontAsset.FontAssetBuilder patch = row.toBuilder();
        boolean dirty = false;
        // Backfill only: rows seeded before web_stack existed have null here. An existing value is the
        // operator's, so it is never overwritten.
        if (isBlank(row.getWebStack()) && def.webStack() != null) {
            patch.webStack(def.webStack());
            dirty = true;
        }
        if (isBlank(row.getWebUrl())) {
            String stored = promote(resource, def);
            if (stored != null) {
                patch.webUrl(stored);
                dirty = true;
            }
        }
        if (dirty) {
            fontAssetRepository.save(patch.build()); // id present → UPDATE
        }
    }

    /**
     * Upload the bundled binary to shared storage and return its public URL, or null when the backend
     * returns a disk path instead (local profile) — the CSS fallback stack still applies in that case.
     * Storage failures propagate to {@link #run} and are logged there, per font.
     */
    private String promote(ClassPathResource resource, SystemFont def) {
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled font " + def.classpathKey(), e);
        }
        String stored = imageStorageService.uploadShared(bytes, "fonts", basename(def.classpathKey()), "font/ttf");
        if (stored == null || !stored.startsWith("http")) {
            log.info("Shared font storage returned a non-public value for {}; keeping fallback stack only",
                    def.familyKey());
            return null;
        }
        log.info("Promoted system font to public URL: {}", stored);
        return stored;
    }

    private static String basename(String classpathKey) {
        int slash = classpathKey.lastIndexOf('/');
        return slash < 0 ? classpathKey : classpathKey.substring(slash + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
