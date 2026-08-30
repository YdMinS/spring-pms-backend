package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds a single default {@link DetailTemplate} on startup so detail generation (Step 2, phase 08) always
 * resolves a template. Idempotent: if an active default already exists it does nothing (two runs still
 * leave exactly one row). Mirror of {@link DefaultTemplateSeeder}.
 *
 * <p>Runs {@code @Order(52)} — right after {@link DefaultTemplateSeeder} ({@code @Order(51)}). No
 * {@code @Profile} — the default template is needed in every environment (dev/prod included).</p>
 *
 * <p>⚠️ {@code DetailTemplate} IS {@code @TenantId} with a NOT NULL {@code tenant_id}, so a
 * {@code tenantId=null} system row (the font-seeder approach) would violate the constraint. This uses the
 * {@link com.pms.config.LocalDataSeeder} pattern — {@code TenantContext.set(1L)} → seed → {@code clear()}
 * in a {@code finally}. Seeds tenant 1 only (current single-tenant assumption).</p>
 */
@Slf4j
@Component
@Order(52)
@RequiredArgsConstructor
public class DefaultDetailTemplateSeeder implements ApplicationRunner {

    private static final Long SEED_TENANT_ID = 1L;

    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    public void run(ApplicationArguments args) {
        TenantContext.set(SEED_TENANT_ID);
        try {
            if (detailTemplateRepository.findByIsDefaultTrueAndActiveTrue().isPresent()) {
                return;
            }
            detailTemplateRepository.save(DetailTemplate.builder()
                    .name("기본 상세 템플릿")
                    .blocks(List.of(
                            text("brandName", null, "center"),
                            text("productName", null, "center"),
                            text("freeShipping", "무료배송", "center"),
                            spacer(24),
                            imageZone("product_photos"),
                            spacer(24),
                            imageZone("detail_photos")))
                    .active(true)
                    .isDefault(true)
                    .build());
            log.info("Seeded default detail template (tenant {})", SEED_TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }

    private DetailBlock text(String bind, String defaultValue, String align) {
        return DetailBlock.builder()
                .type("text")
                .bind(bind)
                .defaultValue(defaultValue)
                .align(align)
                .build();
    }

    private DetailBlock imageZone(String zoneId) {
        return DetailBlock.builder()
                .type("imageZone")
                .bind(zoneId)
                .widthPercent(100)
                .build();
    }

    private DetailBlock spacer(int heightPx) {
        return DetailBlock.builder()
                .type("spacer")
                .heightPx(heightPx)
                .build();
    }
    // NOTE: asset blocks (shipping/refund notices) are NOT seeded — a TemplateAsset must be uploaded by
    // the tenant first. Adding asset blocks is a later template-editor concern; the renderer supports them
    // (covered by unit tests).
}
