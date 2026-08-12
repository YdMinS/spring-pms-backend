package com.pms.service;

import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultTemplateSeeder idempotency: running it twice leaves exactly one active default template.
 * Real context (font seeded by {@link SystemFontSeeder}). Non-{@code @Transactional} — the seeder
 * already ran once at startup, so this asserts re-runs do not duplicate.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultTemplateSeederTest {

    @Autowired private DefaultTemplateSeeder seeder;
    @Autowired private ThumbnailTemplateRepository templateRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void run_isIdempotent_singleDefaultTemplate() {
        // Each run() sets tenant 1 then clears it in finally, so re-set before the assertion queries
        // (otherwise @TenantId filters under NO_TENANT and returns nothing).
        seeder.run(null);
        seeder.run(null);

        TenantContext.set(1L);
        assertThat(templateRepository.findByIsDefaultTrueAndActiveTrue()).isPresent();
        assertThat(templateRepository.count()).isEqualTo(1);
    }
}
