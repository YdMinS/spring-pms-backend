package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structure sync implementation. See {@link MasterOptionChannelSync} for the rules — they are the contract,
 * not implementation detail.
 *
 * <p>⚠️ Query budget for the three master-scoped hooks: the cell list and the channel options of ALL those
 * cells are each read <b>once</b> ({@code findByMasterProductId} + {@code findByProductListingIdIn}) and
 * matched in memory. Reading options per cell would be an N+1 on a master with many channels.
 * {@link #syncStructure} is cell-scoped and follows its own two-query rule.</p>
 *
 * <p>No explicit {@code flush()} between the BOM writes and {@code recalculateOptionPrices}: the cost sum
 * reads the lines through a query, so JPA auto-flush covers it (same reasoning as the 84 re-sync).
 * {@code ChannelAddServiceImpl} flushes because it runs the whole {@code regenerateAssets} seam.</p>
 */
@Component
@RequiredArgsConstructor
public class MasterOptionChannelSyncImpl implements MasterOptionChannelSync {

    private static final Logger log = LoggerFactory.getLogger(MasterOptionChannelSyncImpl.class);

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ListingAssetService listingAssetService;

    @Override
    public void onOptionCreated(Long masterId, MasterProductOption option) {
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;     // no channel yet (e.g. right after master creation) → nothing to propagate to
        }
        Map<Long, List<ProductListingOption>> optionsByCell = optionsByCell(cells);
        // Master items are the BOM source for every cell — read once, copied N times.
        List<MasterProductOptionItem> items = masterProductOptionItemRepository.findByOptionId(option.getId());

        for (ProductListing cell : cells) {
            ProductListingOption existing =
                    match(optionsByCell.get(cell.getId()), option.getName());
            if (existing != null) {
                rebuildLines(existing, items);      // re-added option: reuse the row, `active` untouched
            } else {
                createCellOption(cell, option.getName(), items);
            }
            // Both branches changed the cell's composition → the placeholder/stale price must be re-derived.
            listingAssetService.recalculateOptionPrices(cell);
        }
    }

    @Override
    public void onOptionRenamed(Long masterId, String oldName, String newName) {
        if (Objects.equals(oldName, newName)) {
            return;     // nothing moved → no writes
        }
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;
        }
        for (List<ProductListingOption> cellOptions : optionsByCell(cells).values()) {
            boolean newNameTaken = cellOptions.stream()
                    .anyMatch(cellOption -> Objects.equals(newName, cellOption.getOptionName()));
            for (ProductListingOption cellOption : cellOptions) {
                if (!Objects.equals(oldName, cellOption.getOptionName())) {
                    continue;
                }
                if (newNameTaken) {
                    // Legacy channel rows may already hold both names; renaming would create a duplicate
                    // match key, and every master↔channel match is "first wins" (non-deterministic).
                    log.warn("[OPTION-SYNC] cellId={} rename '{}'->'{}' skipped: name already present",
                            cellOption.getProductListing().getId(), oldName, newName);
                    continue;
                }
                productListingOptionRepository.save(cellOption.toBuilder().optionName(newName).build());
            }
        }
    }

    @Override
    public void onOptionRemoved(Long masterId, String optionName) {
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;
        }
        for (List<ProductListingOption> cellOptions : optionsByCell(cells).values()) {
            for (ProductListingOption cellOption : cellOptions) {
                if (Objects.equals(optionName, cellOption.getOptionName())) {
                    deactivate(cellOption);
                }
            }
        }
        // No recalculateOptionPrices: switching one option off does not move the others' prices.
    }

    @Override
    public void syncStructure(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return;     // legacy cell with no master → nothing to reconcile against
        }
        List<MasterProductOption> masterOptions =
                masterProductOptionRepository.findByMasterProductId(master.getId());
        List<ProductListingOption> cellOptions =
                productListingOptionRepository.findByProductListingId(cell.getId());

        boolean changed = false;

        // (1) missing: in the master, not on this cell → create, switched off (see the interface rules).
        for (MasterProductOption masterOption : masterOptions) {
            if (match(cellOptions, masterOption.getName()) == null) {
                createCellOption(cell, masterOption.getName(),
                        masterProductOptionItemRepository.findByOptionId(masterOption.getId()));
                changed = true;
            }
        }

        // (2) orphan: on this cell, no longer in the master → switch off (row kept).
        Set<String> masterNames = masterOptions.stream()
                .map(MasterProductOption::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        for (ProductListingOption cellOption : cellOptions) {
            if (masterNames.contains(cellOption.getOptionName())
                    || !Boolean.TRUE.equals(cellOption.getActive())) {
                continue;   // still owned by the master, or already off → no write
            }
            if (cell.getPlatformProductId() != null) {
                // Really on sale on the market: switching it off here only desynchronises screen from market.
                log.warn("[OPTION-SYNC] cellId={} active orphan option '{}' left as-is (on market)",
                        cell.getId(), cellOption.getOptionName());
                continue;
            }
            deactivate(cellOption);
            changed = true;
        }

        if (changed) {
            listingAssetService.recalculateOptionPrices(cell);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Channel options of every given cell in ONE query, grouped by cell id (N+1 guard). */
    private Map<Long, List<ProductListingOption>> optionsByCell(Collection<ProductListing> cells) {
        List<Long> cellIds = cells.stream().map(ProductListing::getId).toList();
        Map<Long, List<ProductListingOption>> grouped = new LinkedHashMap<>();
        for (Long cellId : cellIds) {
            grouped.put(cellId, new ArrayList<>());   // every cell gets an entry (even if empty)
        }
        for (ProductListingOption option : productListingOptionRepository.findByProductListingIdIn(cellIds)) {
            List<ProductListingOption> bucket = grouped.get(option.getProductListing().getId());
            if (bucket != null) {
                bucket.add(option);
            }
        }
        return grouped;
    }

    /** {@code optionName} is the master↔channel match key; first match wins (names are unique per master). */
    private static ProductListingOption match(List<ProductListingOption> cellOptions, String optionName) {
        if (cellOptions == null) {
            return null;
        }
        return cellOptions.stream()
                .filter(cellOption -> Objects.equals(optionName, cellOption.getOptionName()))
                .findFirst().orElse(null);
    }

    /**
     * New channel option row. Mirrors the {@code ChannelAddServiceImpl} copy rule with ONE difference:
     * {@code active} is set to {@code false} explicitly — the entity default is {@code true}, which is the
     * channel-creation default and must not be inherited here.
     */
    private void createCellOption(ProductListing cell, String optionName,
                                  List<MasterProductOptionItem> items) {
        ProductListingOption created = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell)
                .optionName(optionName)
                .sellingPrice(BigDecimal.ZERO)          // placeholder; recalculateOptionPrices fills the real one
                .active(false)                          // ⚠️ never auto-sell a newly propagated option
                .platformOptionId(null)                 // issued by the 3c push
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .build());
        copyLines(created, items);
    }

    /**
     * Replace (never merge) the option's BOM lines with the master items — see the interface note on why
     * {@link OptionQuantitySync} is the wrong tool here.
     */
    private void rebuildLines(ProductListingOption cellOption, List<MasterProductOptionItem> items) {
        productListingProductRepository.deleteByProductListingOptionId(cellOption.getId());
        copyLines(cellOption, items);
    }

    private void copyLines(ProductListingOption cellOption, List<MasterProductOptionItem> items) {
        for (MasterProductOptionItem item : items) {
            productListingProductRepository.save(ProductListingProduct.builder()
                    .productListingOption(cellOption)
                    .product(item.getProduct())
                    .quantity(item.getQuantity())
                    .build());
        }
    }

    /** Switch an option off, keeping the row. Already-off options are not re-saved. */
    private void deactivate(ProductListingOption cellOption) {
        if (!Boolean.TRUE.equals(cellOption.getActive())) {
            return;
        }
        productListingOptionRepository.save(cellOption.toBuilder().active(false).build());
    }
}
