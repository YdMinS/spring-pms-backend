package com.pms.migration;

import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.listing.ListingMarketValueService;
import com.pms.service.listing.ListingMarketValueService.BackfillOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One-off migration: read each listed channel cell's product from its market once and store the 「마켓 값 사용」 groups
 * (and a blank 「등록상품명」) — FEATURE_2611_27 / D21 · D32 · R14. Remove with
 * {@link ListingMarketValueService#backfillFromMarket} in the next release.
 *
 * <p><b>Why a runner</b>: the cells imported before this feature never stored the values their market sends. There is
 * no screen or endpoint for this (D21) — a person picks the cell ids right before the deploy, and the work runs once at
 * boot. The judgement and the save are {@link ListingMarketValueService#backfillFromMarket} — this class only decides
 * which cells and when.</p>
 *
 * <p><b>Gate</b>: {@code oclyx.migration.market-values-backfill-enabled} (default {@code false}) and the comma-separated
 * cell ids {@code oclyx.migration.market-values-backfill-listing-ids}. Deploy with it on, let it run, then turn it off
 * again (same routine as {@link OrderAmountBackfillRunner}). ⚠️ A <b>field gate</b>, not a conditional bean:
 * without the bean, "does nothing when off" could not be pinned by a test.</p>
 *
 * <p>🔴 <b>Non-web context</b>: at boot there is no SecurityContext/JWT, so {@link TenantContext} is empty and every
 * tenant-scoped read would silently find nothing. So every tenant is visited with an explicit set → finally clear, and
 * an id is tried in each tenant until one finds it. ⚠️ Do not pin {@code TenantContext.set(1L)}.</p>
 *
 * <p><b>Idempotent</b>: a cell with any of the three flags already on is skipped. Cells run one at a time (no
 * parallelism — D32); the Coupang client already queues calls per vendor. One cell's failure skips only that cell and
 * is logged as WARN.</p>
 */
@Slf4j
@Component
public class MarketValueBackfillRunner implements ApplicationRunner {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingMarketValueService listingMarketValueService;
    private final boolean enabled;
    private final List<Long> listingIds;

    public MarketValueBackfillRunner(MarketplaceAccountRepository marketplaceAccountRepository,
                                     ListingMarketValueService listingMarketValueService,
                                     @Value("${oclyx.migration.market-values-backfill-enabled:false}") boolean enabled,
                                     @Value("${oclyx.migration.market-values-backfill-listing-ids:}")
                                     List<Long> listingIds) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.listingMarketValueService = listingMarketValueService;
        this.enabled = enabled;
        this.listingIds = listingIds == null ? List.of() : listingIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (listingIds.isEmpty()) {
            log.warn("[market-values-backfill] enabled but no listing ids");
            return;
        }

        int filled = 0;
        int alreadyOn = 0;
        int failed = 0;
        Set<Long> found = new LinkedHashSet<>();

        for (Long tenantId : marketplaceAccountRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                for (Long id : listingIds) {
                    if (found.contains(id)) {
                        continue;
                    }
                    try {
                        BackfillOutcome outcome = listingMarketValueService.backfillFromMarket(id);
                        if (outcome == BackfillOutcome.FILLED) {
                            filled++;
                            found.add(id);
                            log.info("[market-values-backfill] listing {} filled", id);
                        } else if (outcome == BackfillOutcome.ALREADY_ON) {
                            alreadyOn++;
                            found.add(id);
                            log.info("[market-values-backfill] listing {} already uses market values", id);
                        }
                        // NOT_FOUND: not in this tenant — tried again in the next one.
                    } catch (Exception e) {
                        failed++;
                        found.add(id);
                        log.warn("[market-values-backfill] listing {} failed: {}", id, e.getMessage());
                    }
                }
            } finally {
                // ⚠️ thread-pool reuse — a leaked tenant id is cross-tenant data exposure.
                TenantContext.clear();
            }
        }

        int notFound = 0;
        for (Long id : listingIds) {
            if (!found.contains(id)) {
                notFound++;
                log.warn("[market-values-backfill] listing {} not found in any tenant", id);
            }
        }

        log.info("[market-values-backfill] DONE — filled={} alreadyOn={} notFound={} failed={}",
                filled, alreadyOn, notFound, failed);
    }
}
