package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.PropagateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

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
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ListingAssetService listingAssetService;
    private final OptionQuantitySync optionQuantitySync;
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
        //    switch off orphans it no longer has. Runs first so the quantity sync below already sees them.
        //    ⚠️ Cell-scoped on purpose — this method runs once per cell in its own REQUIRES_NEW transaction.
        masterOptionChannelSync.syncStructure(cell);
        // 2. Sync matched-option BOM quantities from the master (quantities of already-matched lines).
        syncOptionQuantities(cell);
        // 3. Re-generate assets via the 03 seam (thumbnail / detail stub / per-option selling price). Reuse-only.
        listingAssetService.regenerateAssets(cell);
        // 4. Mark on-market cells pending; DRAFT cells (no market id) are never marked.
        if (cell.getPlatformProductId() != null) {
            productListingRepository.save(cell.toBuilder().needsMarketSync(true).build());
        }
    }

    /**
     * Sync BOM line quantities from the master to matched cell options. Match cell option ↔ master option by
     * {@code optionName}; within a matched option, match BOM lines to master items by {@code productId} and
     * update the quantity only where both sides have that product. A cell-only product is left as-is; a
     * master-only product is skipped; an unmatched cell option (no same-named master option) is skipped.
     *
     * <p>⚠️ Quantities only — <b>option structure</b> (missing options, orphans) is reconciled one step
     * earlier by {@link MasterOptionChannelSync#syncStructure} (86), not here.</p>
     */
    private void syncOptionQuantities(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return;
        }
        Map<String, MasterProductOption> masterOptionsByName = masterProductOptionRepository
                .findByMasterProductId(master.getId()).stream()
                .collect(Collectors.toMap(MasterProductOption::getName, o -> o, (first, dup) -> first));

        for (ProductListingOption cellOption : productListingOptionRepository.findByProductListingId(cell.getId())) {
            MasterProductOption masterOption = masterOptionsByName.get(cellOption.getOptionName());
            if (masterOption == null) {
                continue;   // unmatched option → skip
            }
            // Shared line rule (84): quantities only, matched by productId. Never re-implement here.
            optionQuantitySync.syncLines(cellOption, masterOption);
        }
    }
}
