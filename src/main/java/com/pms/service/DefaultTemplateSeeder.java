package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.FontAssetRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds a single default {@link ThumbnailTemplate} on startup so thumbnail generation always resolves
 * a template ({@code ProductThumbnailServiceImpl.resolveTemplate}). Idempotent: if an active default
 * already exists it does nothing (two runs still leave exactly one row).
 *
 * <p>Runs {@code @Order(51)} — right after {@link SystemFontSeeder} ({@code @Order(50)}), because it
 * binds the seeded system font by family key.</p>
 *
 * <p>⚠️ Tenant handling differs from {@link SystemFontSeeder}: {@code ThumbnailTemplate} IS
 * {@code @TenantId} with a NOT NULL {@code tenant_id}, so a {@code tenantId=null} system row (the font
 * approach) would violate the constraint. Instead this uses the {@link com.pms.config.LocalDataSeeder}
 * pattern — {@code TenantContext.set(1L)} → seed → {@code clear()} in a {@code finally}. Unlike
 * {@code LocalDataSeeder} there is NO {@code @Profile} — the default template is needed in every
 * environment (dev/prod included).</p>
 *
 * <p>Seeds tenant 1 only (current single-tenant assumption). Per-tenant default provisioning for a
 * multi-tenant rollout is out of scope here and would hook into tenant provisioning.</p>
 */
@Slf4j
@Component
@Order(51)
@RequiredArgsConstructor
public class DefaultTemplateSeeder implements ApplicationRunner {

    private static final Long SEED_TENANT_ID = 1L;
    private static final String SYSTEM_FONT_FAMILY = SystemFontSeeder.SYSTEM_FAMILY;

    private final ThumbnailTemplateRepository templateRepository;
    private final FontAssetRepository fontAssetRepository;

    @Override
    public void run(ApplicationArguments args) {
        // Non-web startup context → TenantContext is empty (NO_TENANT). Set tenant 1 so the @TenantId
        // template inserts/filters under tenant 1; clear afterwards (thread-pool hygiene).
        TenantContext.set(SEED_TENANT_ID);
        try {
            if (templateRepository.findByIsDefaultTrueAndActiveTrue().isPresent()) {
                return;
            }
            Long fontId = fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(SYSTEM_FONT_FAMILY)
                    .map(FontAsset::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "System font not seeded — DefaultTemplateSeeder must run after SystemFontSeeder"));

            templateRepository.save(ThumbnailTemplate.builder()
                    .name("기본 템플릿")
                    .canvasWidth(1000)
                    .canvasHeight(1000)
                    .elements(List.of(brandTop(fontId), productBottom(fontId)))
                    .active(true)
                    .isDefault(true)
                    .build());
            log.info("Seeded default thumbnail template (tenant {})", SEED_TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }

    /** Brand name, centered in the top band. */
    private TemplateElement brandTop(Long fontId) {
        return TemplateElement.builder()
                .type("text")
                .bind("brandName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(1000).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("top").build())
                .fontId(fontId)
                .color("#000000")
                .maxFontSize(64)
                .minFontSize(24)
                .maxLines(2)
                .build();
    }

    /** Product name, centered in the bottom band. */
    private TemplateElement productBottom(Long fontId) {
        return TemplateElement.builder()
                .type("text")
                .bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(800).w(1000).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("bottom").build())
                .fontId(fontId)
                .color("#000000")
                .maxFontSize(64)
                .minFontSize(24)
                .maxLines(2)
                .build();
    }
}
