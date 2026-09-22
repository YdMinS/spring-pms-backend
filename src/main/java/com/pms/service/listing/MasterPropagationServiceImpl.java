package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.dto.response.PropagateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Layer A: local propagation of master changes to linked channel cells (FEATURE_2608_06 / 3d). See
 * {@link MasterPropagationService} for the design + the {@code REQUIRES_NEW} isolation rationale.
 *
 * <p>⚠️ The class is intentionally NOT {@code @Transactional}: {@link #propagate(Long)} is a plain loop that
 * calls {@link #propagateOne(ProductListing)} through {@link #self} (the injected proxy) so each cell commits
 * independently. The auto-trigger ({@code MasterProductServiceImpl.update}) calls {@code propagate} inside its
 * own write transaction; {@code REQUIRES_NEW} suspends it, so a failed cell never rolls the master save back.</p>
 */
@Service
@RequiredArgsConstructor
public class MasterPropagationServiceImpl implements MasterPropagationService {

    private static final Logger log = LoggerFactory.getLogger(MasterPropagationServiceImpl.class);

    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ListingAssetService listingAssetService;
    private final MasterOptionChannelSync masterOptionChannelSync;

    /** Self proxy so {@link #propagateOne} goes through the {@code REQUIRES_NEW} advice (not a direct call). */
    @Autowired
    @Lazy
    private MasterPropagationService self;

    @Override
    public PropagateResponse propagate(Long masterId) {
        // Tenant-scoped existence check (findScopedById is @TenantId-filtered → cross-tenant/absent = 404).
        masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));

        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        int propagated = 0, skipped = 0, failed = 0;
        for (ProductListing cell : cells) {
            // Only cells that were previously generated (propagation = re-generation, not first creation).
            if (generatedProductDataRepository.findByProductListingId(cell.getId()).isEmpty()) {
                skipped++;
                continue;
            }
            try {
                self.propagateOne(cell);   // proxy → REQUIRES_NEW: independent commit per cell
                propagated++;
            } catch (Exception e) {
                failed++;   // isolate: one failed cell must not abort the whole propagation
                log.warn("[MASTER-PROPAGATE] masterId={} cellId={} propagate failed: {}",
                        masterId, cell.getId(), e.getMessage());
            }
        }
        return PropagateResponse.builder().propagated(propagated).skipped(skipped).failed(failed).build();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void propagateOne(ProductListing cell) {
        // 1. Reconcile this cell's option STRUCTURE with the master (86): create options the master gained,
        //    ⚠️ Cell-scoped on purpose — this method runs once per cell in its own REQUIRES_NEW transaction.
        masterOptionChannelSync.syncStructure(cell);
        // 2. 2609_71: 수량 동기화 단계는 사라졌다 — 셀에 구성품 사본이 없어 맞출 대상이 없다. 구성품은
        //    master_product_option_id FK 를 타고 마스터 옵션의 items 를 그대로 따른다.
        // 3. Re-generate assets via the 03 seam (thumbnail / detail stub / per-option selling price). Reuse-only.
        listingAssetService.regenerateAssets(cell);
        // 4. Mark on-market cells pending; DRAFT cells (no market id) are never marked.
        if (cell.getPlatformProductId() != null) {
            productListingRepository.save(cell.toBuilder().needsMarketSync(true).build());
        }
    }
}
