package com.pms.tenant;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Font tenant isolation (MUST-KEEP). {@link FontAsset} is deliberately NOT {@code @TenantId} — system
 * fonts share {@code tenant_id=null}, which Hibernate's discriminator cannot match. Isolation is
 * therefore enforced by {@link FontAssetRepository#findSystemAndTenant}: system ∪ current tenant, and a
 * tenant never sees another tenant's font. Self-contained (creates its own rows, deletes them by id) so
 * it does not disturb the seeded system font.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Font tenant isolation (system ∪ tenant, no cross-tenant leak)")
class FontAssetTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;

    @Autowired
    private FontAssetRepository fontAssetRepository;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(fontAssetRepository::deleteById);
        createdIds.clear();
    }

    @Test
    @DisplayName("tenant sees system + own fonts; not another tenant's")
    void listingIsScopedToSystemAndTenant() {
        Long sys = save(null, "isoSys", FontSource.BUNDLED);
        Long a = save(TENANT_1, "isoA", FontSource.UPLOADED);
        Long b = save(TENANT_2, "isoB", FontSource.UPLOADED);

        assertThat(fontAssetRepository.findSystemAndTenant(TENANT_1))
                .extracting(FontAsset::getId)
                .contains(sys, a)     // system + own
                .doesNotContain(b);   // not tenant 2's

        assertThat(fontAssetRepository.findSystemAndTenant(TENANT_2))
                .extracting(FontAsset::getId)
                .contains(sys, b)
                .doesNotContain(a);
    }

    private Long save(Long tenantId, String family, FontSource source) {
        FontAsset saved = fontAssetRepository.save(FontAsset.builder()
                .tenantId(tenantId)
                .displayName(family)
                .familyKey(family)
                .source(source)
                .storageKey("fonts/" + family + ".ttf")
                .build());
        createdIds.add(saved.getId());
        return saved.getId();
    }
}
