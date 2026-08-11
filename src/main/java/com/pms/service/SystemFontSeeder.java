package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the shared system font(s) on startup (idempotent, keyed by family). {@link FontAsset} is not
 * {@code @TenantId}, so a {@code tenantId=null} system row inserts fine without any TenantContext —
 * this runs in a non-web startup context.
 *
 * <p>Ships pointing at {@code fonts/system-sans.ttf}; until a real OFL binary is added there (see
 * {@code resources/fonts/README.md}), {@link FontRegistry} resolves the family key to a JDK logical
 * font, so rendering works regardless.</p>
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class SystemFontSeeder implements ApplicationRunner {

    private static final String SYSTEM_FAMILY = "SansSerif";
    private static final String SYSTEM_STORAGE_KEY = "fonts/system-sans.ttf";

    private final FontAssetRepository fontAssetRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(SYSTEM_FAMILY).isPresent()) {
            return;
        }
        fontAssetRepository.save(FontAsset.builder()
                .tenantId(null) // system-shared
                .displayName("System Sans")
                .familyKey(SYSTEM_FAMILY)
                .source(FontSource.BUNDLED)
                .storageKey(SYSTEM_STORAGE_KEY)
                .build());
        log.info("Seeded system font: {}", SYSTEM_FAMILY);
    }
}
