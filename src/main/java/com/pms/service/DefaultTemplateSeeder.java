package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.FontAsset;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
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
 * a template ({@link ChannelTemplateResolver}). Idempotent: if an active default
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
                    .backgroundMode(BackgroundMode.WHITE)
                    .elements(List.of(productBase(), brandTop(fontId), productBottom(fontId)))
                    .fields(List.of(
                            field("brandName", "브랜드명", ""),
                            field("productName", "상품명", "")))
                    .active(true)
                    .isDefault(true)
                    .build());
            log.info("Seeded default thumbnail template (tenant {})", SEED_TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }

    /** Reserved input field (brandName/productName): blank defaultValue, filled by the generate UI. */
    private TemplateField field(String key, String label, String defaultValue) {
        return TemplateField.builder().key(key).label(label).defaultValue(defaultValue).build();
    }

    // NOTE: lineSpacing 1.15 + align v=center are the multi-line preset (long/large text wraps instead
    // of shrinking, block stays centered in its band whether it renders as 1 or 2 lines). Seeder is
    // idempotent, so this only affects newly seeded templates — existing environments adjust in the editor.

    /**
     * Product photo base layer — full canvas, drawn bottom-most. Contain-fit so the long side meets the
     * canvas and the margins show the background; text bands overlay on top. The renderer forces any
     * {@code productImage} element to the base regardless of array position, but seeding it first keeps
     * the array's z-order self-explanatory.
     */
    private TemplateElement productBase() {
        return TemplateElement.builder()
                .type("image")
                .bind("productImage")
                .region(TemplateElement.Region.builder().x(0).y(0).w(1000).h(1000).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .opacity(1.0)
                .build();
    }

    /** Brand name, centered in the top band. */
    private TemplateElement brandTop(Long fontId) {
        return TemplateElement.builder()
                .type("text")
                .bind("brandName")
                .region(TemplateElement.Region.builder().x(0).y(0).w(1000).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(fontId)
                .color("#000000")
                .maxFontSize(64)
                .minFontSize(24)
                .maxLines(2)
                .lineSpacing(1.15)
                .build();
    }

    /** Product name, centered in the bottom band. */
    private TemplateElement productBottom(Long fontId) {
        return TemplateElement.builder()
                .type("text")
                .bind("productName")
                .region(TemplateElement.Region.builder().x(0).y(800).w(1000).h(200).build())
                .align(TemplateElement.Align.builder().h("center").v("center").build())
                .fontId(fontId)
                .color("#000000")
                .maxFontSize(64)
                .minFontSize(24)
                .maxLines(2)
                .lineSpacing(1.15)
                .build();
    }
}
