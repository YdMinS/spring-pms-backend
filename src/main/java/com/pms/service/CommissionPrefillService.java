package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Prefills a default {@link CommissionRate} for a standard category from the Coupang static fee table
 * (FEATURE_2608_06 / 46) — "prefill" = seed an initial value only when none exists (the user may edit it
 * afterwards). Only COUPANG is covered (other marketplaces stay manual until their own fee table ships).
 *
 * <p>⚠️ Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so a DB-level prefill failure marks
 * only this transaction rollback-only — it never propagates to the caller's mapping-upsert transaction.
 * The caller ({@code CategoryMappingServiceImpl}) additionally swallows any exception (best-effort), so a
 * failed prefill never fails the mapping save.</p>
 *
 * <p>The written rate is {@code isDefault=false} (a category-specific actual-rate slot, seeded from the
 * 2019 default). That flag is unrelated to "prefill" — it just means this is the per-category rate row,
 * not the platform-wide default row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionPrefillService {

    private static final String COUPANG = "COUPANG";

    private final CommissionRateRepository commissionRateRepository;
    private final CategoryRepository categoryRepository;
    private final CoupangFeeResolver coupangFeeResolver;

    /**
     * Seeds a COUPANG {@link CommissionRate} for {@code categoryId} when absent and the fee table resolves
     * a rate for {@code platformCategoryName}. No-op when: platform ≠ COUPANG, a rate already exists, the
     * category is gone, or the fee table has no match.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prefillIfAbsent(Long categoryId, String platform, String platformCategoryName) {
        if (!COUPANG.equalsIgnoreCase(platform)) {
            return;
        }
        // A rate already exists (possibly user-edited) → never overwrite.
        if (commissionRateRepository.findByPlatformAndCategoryId(COUPANG, categoryId).isPresent()) {
            return;
        }
        Optional<BigDecimal> rate = coupangFeeResolver.resolve(platformCategoryName);
        if (rate.isEmpty()) {
            return;
        }
        Category category = categoryRepository.findById(categoryId).orElse(null);
        if (category == null) {
            return;
        }
        commissionRateRepository.save(CommissionRate.builder()
                .platform(COUPANG)
                .category(category)
                .rate(rate.get())
                .isDefault(false)
                .build());
        log.info("Prefilled COUPANG commission rate {} for category {}", rate.get(), categoryId);
    }
}
