package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
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
 * <p>2609_71: 이 컴포넌트는 더 이상 셀 BOM 을 쓰지 않는다 — 구성품은 마스터 옵션이 갖고
 * {@code master_product_option_id} FK 하나로 따라온다. 남은 책임은 <b>옵션 행의 구조</b>(생성·이름·on/off)와
 * 구성이 바뀐 뒤의 판매가 재계산이다.</p>
 */
@Component
@RequiredArgsConstructor
public class MasterOptionChannelSyncImpl implements MasterOptionChannelSync {

    private static final Logger log = LoggerFactory.getLogger(MasterOptionChannelSyncImpl.class);

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final ListingAssetService listingAssetService;

    @Override
    public void onOptionCreated(Long masterId, MasterProductOption option) {
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;     // no channel yet (e.g. right after master creation) → nothing to propagate to
        }
        Map<Long, List<ProductListingOption>> optionsByCell = optionsByCell(cells);

        for (ProductListing cell : cells) {
            if (match(optionsByCell.get(cell.getId()), option.getId()) == null) {
                createCellOption(cell, option);
            }
            // 2609_71: 구성품은 마스터 옵션이 갖는다 — 셀에 복사할 것이 없다. 그래도 이 셀의 원가 합이
            // 바뀌었으므로(옵션이 새로 붙었거나 다시 켜졌다) 판매가는 다시 계산한다.
            listingAssetService.recalculateOptionPrices(cell);
        }
    }

    @Override
    public void onOptionRenamed(Long masterId, Long masterOptionId, String newName) {
        if (masterOptionId == null) {
            return;
        }
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;
        }
        for (List<ProductListingOption> cellOptions : optionsByCell(cells).values()) {
            boolean newNameTaken = cellOptions.stream()
                    .filter(cellOption -> !masterOptionId.equals(masterOptionId(cellOption)))
                    .anyMatch(cellOption -> Objects.equals(newName, cellOption.getOptionName()));
            for (ProductListingOption cellOption : cellOptions) {
                if (!masterOptionId.equals(masterOptionId(cellOption))) {
                    continue;   // 2609_22/D1: the FK is the match key — the cell's own name is irrelevant here
                }
                // 2609_22/D4: 채널에서 직접 정한 이름은 마스터 rename 이 덮어쓰지 않는다([옵션명 일괄 적용]이 되돌린다).
                if (cellOption.getOptionNameSource() == GeneratedContentSource.MANUAL_OVERRIDE) {
                    continue;
                }
                if (Objects.equals(newName, cellOption.getOptionName())) {
                    continue;   // already there → no write
                }
                if (newNameTaken) {
                    // A MANUAL_OVERRIDE sibling (or a legacy duplicate) already carries that name; two options
                    // with the same name in one cell are a marketplace error (Coupang itemName).
                    log.warn("[OPTION-SYNC] cellId={} rename to '{}' skipped: name already present",
                            cellOption.getProductListing().getId(), newName);
                    continue;
                }
                productListingOptionRepository.save(cellOption.toBuilder().optionName(newName).build());
            }
        }
    }

    @Override
    public void onOptionRemoved(Long masterId, Long masterOptionId) {
        if (masterOptionId == null) {
            return;
        }
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;
        }
        for (List<ProductListingOption> cellOptions : optionsByCell(cells).values()) {
            for (ProductListingOption cellOption : cellOptions) {
                if (masterOptionId.equals(masterOptionId(cellOption))) {
                    deactivate(cellOption);
                }
            }
        }
        // No recalculateOptionPrices: switching one option off does not move the others' prices.
    }

    @Override
    public void onOptionComponentsChanged(Long masterId, MasterProductOption option) {
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (cells.isEmpty()) {
            return;
        }
        Map<Long, List<ProductListingOption>> optionsByCell = optionsByCell(cells);

        for (ProductListing cell : cells) {
            if (match(optionsByCell.get(cell.getId()), option.getId()) == null) {
                continue;   // this channel does not carry the option → creating it is not this hook's job
            }
            // 2609_71: 셀 BOM 사본이 없으므로 교체할 줄이 없다. 마스터 items 가 곧 셀의 구성품이고,
            // 새 구성으로 계산한 원가 합이 판매가에 반영되도록 여기서 재계산만 한다.
            listingAssetService.recalculateOptionPrices(cell);
        }
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
            if (match(cellOptions, masterOption.getId()) == null) {
                createCellOption(cell, masterOption);
                changed = true;
            }
        }

        // (2) orphan: linked to a master option this master no longer has → switch off (row kept).
        // 🔴 2609_22/D2: FK null is NOT an orphan — it is a channel-only option and must survive untouched.
        // With the FK's ON DELETE SET NULL (D22) a real orphan is nearly unreachable (a deleted master option
        // nulls the FK), so this loop is effectively a no-op today. Kept for legacy rows and cross-master data.
        Set<Long> masterOptionIds = masterOptions.stream()
                .map(MasterProductOption::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        for (ProductListingOption cellOption : cellOptions) {
            if (cellOption.getMasterProductOption() == null) {
                continue;   // 채널 전용 옵션 → 마스터 전파 대상 아님(D2)
            }
            if (masterOptionIds.contains(masterOptionId(cellOption))
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

    /**
     * 2609_22/D1: {@code master_product_option_id} is the master↔channel match key (never the name); first
     * match wins (one cell carries at most one row per master option).
     */
    private static ProductListingOption match(List<ProductListingOption> cellOptions, Long masterOptionId) {
        if (cellOptions == null || masterOptionId == null) {
            return null;
        }
        return cellOptions.stream()
                .filter(cellOption -> masterOptionId.equals(masterOptionId(cellOption)))
                .findFirst().orElse(null);
    }

    /**
     * The linked master option's id, or null for a channel-only option (D2). ⚠️ Only the id is read so a LAZY
     * proxy never has to be initialised (no extra query per option).
     */
    private static Long masterOptionId(ProductListingOption cellOption) {
        MasterProductOption masterOption = cellOption.getMasterProductOption();
        return masterOption == null ? null : masterOption.getId();
    }

    /**
     * New channel option row. Mirrors the {@code ChannelAddServiceImpl} copy rule with ONE difference:
     * {@code active} is set to {@code false} explicitly — the entity default is {@code true}, which is the
     * channel-creation default and must not be inherited here.
     *
     * <p>2609_71: 구성품은 복사하지 않는다 — FK 하나로 마스터 옵션의 items 가 곧 이 옵션의 구성품이 된다.</p>
     */
    private void createCellOption(ProductListing cell, MasterProductOption masterOption) {
        productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell)
                // 🔴 2609_22/D1: without this FK the new row would be born channel-only and be skipped by
                // propagation, price recalculation and the stock clamp for ever (D2) — silently.
                .masterProductOption(masterOption)
                .optionName(masterOption.getName())
                .sellingPrice(BigDecimal.ZERO)          // placeholder; recalculateOptionPrices fills the real one
                .active(false)                          // ⚠️ never auto-sell a newly propagated option
                .platformOptionId(null)                 // issued by the 3c push
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .build());
    }

    /** Switch an option off, keeping the row. Already-off options are not re-saved. */
    private void deactivate(ProductListingOption cellOption) {
        if (!Boolean.TRUE.equals(cellOption.getActive())) {
            return;
        }
        productListingOptionRepository.save(cellOption.toBuilder().active(false).build());
    }
}
