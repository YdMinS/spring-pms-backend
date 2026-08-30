package com.pms.service.listing;

import com.pms.domain.ProductListing;
import com.pms.dto.response.PropagateResponse;

/**
 * Layer A of the two-layer propagation (FEATURE_2608_06 / 3d, Design 2): local, immediate, safe. A master
 * (content / price-input) change re-generates the linked channel cells' assets — no adapter / market calls
 * (local DB + S3 only). Layer B ({@link ListingPropagationService}) does the gated market push.
 *
 * <p>⚠️ Transaction isolation trap: {@link #propagate(Long)} must NOT wrap the cell loop in a transaction.
 * {@code ListingAssetService.regenerateAssets} is a separate bean with its own {@code @Transactional}
 * (REQUIRED); a cross-bean call inside an open parent transaction marks the parent rollback-only on a caught
 * cell exception → {@code UnexpectedRollbackException} at commit. So each cell is processed by
 * {@link #propagateOne(ProductListing)} in a fresh {@code REQUIRES_NEW} transaction (independent commit,
 * one failed cell rolls back only itself), invoked through the injected proxy — never self-invoked directly
 * (proxy bypass would void REQUIRES_NEW).</p>
 */
public interface MasterPropagationService {

    /**
     * Layer A: re-generate every linked cell that already has generated assets. Non-transactional loop; each
     * cell runs in its own {@code REQUIRES_NEW} transaction (see {@link #propagateOne(ProductListing)}). A
     * per-cell failure is isolated (logged + counted); the parent (e.g. the master update) is never poisoned.
     *
     * @param masterId master product id (tenant-scoped; 404 if absent)
     * @return counts of propagated / skipped / failed cells
     */
    PropagateResponse propagate(Long masterId);

    /**
     * Process one cell in a fresh {@code REQUIRES_NEW} transaction: sync matched-option BOM quantities from the
     * master, {@code regenerateAssets(cell)} (03 seam — thumbnail / detail stub / per-option price recalc), and
     * mark {@code needsMarketSync = true} for on-market cells (DRAFT cells are not marked).
     *
     * <p>⚠️ Must be invoked through the injected proxy (self-reference or another bean) — a direct
     * self-invocation bypasses the proxy and voids {@code REQUIRES_NEW}.</p>
     *
     * @param cell the linked channel cell to propagate to
     */
    void propagateOne(ProductListing cell);
}
