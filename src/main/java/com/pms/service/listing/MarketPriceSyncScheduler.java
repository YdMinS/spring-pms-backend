package com.pms.service.listing;

import com.pms.domain.ProductListing;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.security.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Market price batch — every day 02:05 KST, every Coupang · 11st · ESM listing on a market (FEATURE_2610_27 / D13 ② ·
 * D22~D27 · D34).
 *
 * <p>🔴 <b>Own thread (D22).</b> The scheduler has ONE thread ({@code OrderSyncScheduler}): running the whole read
 * there would delay the 1-minute reserved shipment run and the 15-minute order sync. {@link #schedule} only hands
 * the run to {@link #runner} and returns. The runner is a private single thread — not a bean, so it never becomes
 * a second {@code ExecutorService} bean next to {@code coupangSyncExecutor}.</p>
 *
 * <p>🔴 <b>Cron is a property</b> ({@code oclyx.listing.market-price-sync-cron}, D27) — dev sets {@code "-"} (D25: dev
 * and prod share the Coupang vendor; 11st · ESM calls are off on dev anyway). Interpreted in Asia/Seoul.</p>
 *
 * <p>Failures (D24): each listing is its own transaction ({@link MarketPriceSyncService#syncOne}); a failed listing
 * keeps its previous values. One tenant or one account failing never stops the rest. A Coupang 429
 * ({@link CoupangRateLimitedException}) skips that account's remaining listings for the day. One log line per
 * tenant: read · changed (listings, D34) · failed · skipped.</p>
 *
 * <p>⚠️ Single instance assumed, like every other {@code @Scheduled} here — no distributed lock.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketPriceSyncScheduler {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final MarketPriceSyncService marketPriceSyncService;

    /** D22: the batch's own thread. Daemon so a shutdown is never held by a half-done run. */
    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-price-sync");
        thread.setDaemon(true);
        return thread;
    });

    @Scheduled(cron = "${oclyx.listing.market-price-sync-cron:0 5 2 * * *}", zone = "Asia/Seoul")
    public void schedule() {
        runner.execute(this::runAll);
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** Every tenant in turn; a failing tenant is logged and the next one runs (D24). */
    void runAll() {
        for (Long tenantId : marketplaceAccountRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                Counts counts = runTenant();
                log.info("[MARKET-PRICE-SYNC] tenant={} read={} changed={} failed={} skipped={}",
                        tenantId, counts.read(), counts.changed(), counts.failed(), counts.skipped());
            } catch (Exception e) {
                log.warn("[MARKET-PRICE-SYNC] tenant={} failed", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** The current tenant's listings (D23), one transaction each (D24). */
    Counts runTenant() {
        List<ProductListing> targets = productListingRepository.findMarketPriceTargets();
        Set<String> rateLimitedAccounts = new HashSet<>();
        int read = 0;
        int changed = 0;
        int failed = 0;
        int skipped = 0;
        for (ProductListing cell : targets) {
            String account = cell.getSeller().getId() + "|" + cell.getPlatform().name();
            if (rateLimitedAccounts.contains(account)) {
                skipped++;
                continue;
            }
            try {
                if (marketPriceSyncService.syncOne(cell.getId())) {
                    changed++;
                }
                read++;
            } catch (CoupangRateLimitedException e) {
                rateLimitedAccounts.add(account);
                failed++;
                log.warn("[MARKET-PRICE-SYNC] listingId={} rate limited — skipping the rest of this account today",
                        cell.getId());
            } catch (Exception e) {
                failed++;
                log.warn("[MARKET-PRICE-SYNC] listingId={} failed: {}", cell.getId(), e.getMessage());
            }
        }
        return new Counts(read, changed, failed, skipped);
    }

    /** One tenant's result — every count is in listings (D24 · D34). */
    record Counts(int read, int changed, int failed, int skipped) {
    }
}
