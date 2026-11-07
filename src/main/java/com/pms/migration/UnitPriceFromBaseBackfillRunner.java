package com.pms.migration;

import com.pms.repository.ProductRepository;
import com.pms.security.TenantContext;
import com.pms.service.stock.BasePriceFillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Deploy-time fill of the unit prices that are empty today (FEATURE_2611_17 D22): the purchases (quantity &gt; 0) and
 * lots without a unit price of a product that has a registered price take that price with the 「미기입」 mark, and the
 * product's debts are rewritten.
 *
 * <p><b>Why a runner</b>: changeset 129 only adds the mark column — rewriting the debts of each filled product needs
 * {@code LotLedgerService}, which SQL cannot do.
 *
 * <p><b>No gate</b>: it runs at every start-up and is idempotent — a filled row is never empty again, so a later start
 * finds nothing (two queries per tenant). A product without a registered price stays empty until its price appears
 * ({@link BasePriceFillService#fillEmptyPrices}).
 *
 * <p>🔴 <b>Non-web context</b>: no JWT at boot → {@link TenantContext} is empty and every {@code @TenantId} query would
 * read nothing. Each tenant is set explicitly and cleared in {@code finally} (same pattern as
 * {@link OrderAmountBackfillRunner}).
 * <p>⚠️ A failure is logged per tenant and does not stop the boot — the rows stay empty and the next start retries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitPriceFromBaseBackfillRunner implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final BasePriceFillService basePriceFillService;

    @Override
    public void run(ApplicationArguments args) {
        for (Long tenantId : productRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                int filled = basePriceFillService.fillAllEmptyPrices();
                if (filled > 0) {
                    log.info("[unit-price-from-base] tenant {}: {} row(s) filled", tenantId, filled);
                }
            } catch (RuntimeException e) {
                log.warn("[unit-price-from-base] tenant {} failed: {}", tenantId, e.getMessage());
            } finally {
                // ⚠️ thread-pool reuse — a leaked tenant id is cross-tenant data exposure.
                TenantContext.clear();
            }
        }
    }
}
