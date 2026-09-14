package com.pms.service;

import com.pms.domain.BoxKind;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Default {@link MasterChannelConfigService}. See the interface for the resolution rules and the null-check
 * ownership contract.
 */
@Service
@RequiredArgsConstructor
public class MasterChannelConfigServiceImpl implements MasterChannelConfigService {

    private static final Logger log = LoggerFactory.getLogger(MasterChannelConfigServiceImpl.class);

    private final CategoryMappingRepository categoryMappingRepository;
    private final CategoryRepository categoryRepository;
    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    public Category resolveStandardCategory(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        // ⚠️ LAZY master.category — the caller (PriceCalculator / CoupangListingAdapter / regenerate) runs
        // this inside a @Transactional boundary (open-in-view=false).
        Category category = master == null ? null : master.getCategory();
        if (category == null) {
            throw new IllegalArgumentException("표준 카테고리 미설정");
        }
        return category;
    }

    @Override
    public ChannelCategory resolveChannelCategory(ProductListing cell) {
        // 2609_45/D9·D10: a channel that carries its own marketplace category keeps it — that code IS this
        // channel's category (payload, commission, attribute/notice schema).
        // ⚠️ This reverses 2609_22/D16 ("display only, never in the payload"). Reason: when two sellers'
        //    marketplace products hang off one master, forcing the master's category means the next
        //    [수정 요청] changes that product's category on the mall (re-review), and until then its margin is
        //    computed with someone else's commission rate (measured: product 73170 vs master 58630, 2026-09-14).
        String code = cell.getPlatformCategoryCode();
        if (code == null || code.isBlank()) {
            return masterCategory(cell);
        }
        Optional<PlatformCategory> found =
                platformCategoryRepository.findByPlatformAndCode(cell.getPlatform(), code);
        // D11: the only condition for keeping it — that PlatformCategory must carry a commission. Without one
        // the selling-price reverse-calc is a 400 (PriceCalculator), so fall back to the master quietly.
        if (found.isEmpty() || found.get().getCommissionRate() == null) {
            log.warn("[CATEGORY] cell={} 채널 카테고리 {} 미시드/수수료 없음 → 마스터 카테고리 사용", cell.getId(), code);
            return masterCategory(cell);
        }
        // 🔴 D10-1: own = "differs from the master's resolved code", NOT "the cell has a code". The import
        //    stores platform_category_code whether or not it matches (CoupangListingImportServiceImpl), so
        //    code-presence would mark every already-imported cell as channel-owned and the adapter would then
        //    drop the master attributes from the merge base → those cells' [수정 요청] would go out with no
        //    attributes (or 400). When the master cannot be resolved at all there is nothing to compare
        //    against, so the cell's own category is by definition its own.
        PlatformCategory master;
        try {
            master = resolvePlatformCategory(cell.getMasterProduct(), cell.getPlatform());
        } catch (IllegalArgumentException e) {
            return new ChannelCategory(found.get(), true);
        }
        return new ChannelCategory(found.get(), !found.get().getCode().equals(master.getCode()));
    }

    /** The master-resolved category, always {@code own=false}. Propagates the 400 when the master has none. */
    private ChannelCategory masterCategory(ProductListing cell) {
        return new ChannelCategory(resolvePlatformCategory(cell.getMasterProduct(), cell.getPlatform()), false);
    }

    /** Unchanged signature — delegates, so the six consumers of this seam stay untouched (2609_45/D10). */
    @Override
    public PlatformCategory resolvePlatformCategory(ProductListing cell) {
        return resolveChannelCategory(cell).category();
    }

    @Override
    public String resolvePlatformCategoryCode(ProductListing cell) {
        return resolvePlatformCategory(cell).getCode();
    }

    @Override
    public String resolvePlatformCategoryCode(MasterProduct master, Platform platform) {
        return resolvePlatformCategory(master, platform).getCode();
    }

    @Override
    public String resolvePlatformCategoryCode(Long categoryId, Platform platform) {
        Category standard = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("카테고리 없음"));
        return resolvePlatformCategory(standard, platform).getCode();
    }

    /** Master-arg overload: pulls the master's single standard category and delegates to the core. */
    private PlatformCategory resolvePlatformCategory(MasterProduct master, Platform platform) {
        return resolvePlatformCategory(master == null ? null : master.getCategory(), platform);
    }

    /**
     * Core resolution: standard category → CategoryMapping(platform) → its linked PlatformCategory FK.
     * 400 when there is no standard category, no mapping for the platform, or the mapping is not yet linked
     * to a PlatformCategory (transition — the code/commission owner has not been seeded).
     * ⚠️ LAZY category / mapping.platformCategory — callers run inside a @Transactional boundary.
     */
    private PlatformCategory resolvePlatformCategory(Category standard, Platform platform) {
        if (standard == null) {
            throw new IllegalArgumentException("표준 카테고리 미설정");
        }
        PlatformCategory platformCategory = categoryMappingRepository
                .findByCategoryIdAndPlatform(standard.getId(), platform)
                .map(mapping -> mapping.getPlatformCategory())
                .orElseThrow(() -> new IllegalArgumentException(platform + " 카테고리 매핑 미설정"));
        if (platformCategory == null) {
            throw new IllegalArgumentException(platform + " 카테고리 매핑 미설정");
        }
        return platformCategory;
    }

    @Override
    public CarrierRate resolveDelivery(ProductListing cell, MasterProductOption masterOption) {
        // Java has no ?. — explicit null checks: option override wins, else master default.
        CarrierRate resolved = masterOption != null && masterOption.getDelivery() != null
                ? masterOption.getDelivery()
                : cell.getMasterProduct() == null ? null : cell.getMasterProduct().getDefaultDelivery();
        if (resolved == null || resolved.getCost() == null) {
            throw new IllegalArgumentException("배송 미설정");
        }
        return resolved;
    }

    @Override
    public Package resolvePackage(ProductListing cell, MasterProductOption masterOption) {
        Package resolved = masterOption != null && masterOption.getPackage_() != null
                ? masterOption.getPackage_()
                : cell.getMasterProduct() == null ? null : cell.getMasterProduct().getDefaultPackage();
        if (resolved == null || resolved.getCost() == null) {
            throw new IllegalArgumentException("박스 미설정");
        }
        // 🔴 A recycled box must never reach the selling-price calculation (PLAN 2609_40 D21): it costs 0,
        // so pricing off it would give the goods away. This is DATA being wrong, not a runtime hiccup —
        // do not fall back to another box, make the caller fix the assignment.
        if (resolved.getBoxKind() == BoxKind.RECYCLED) {
            throw new IllegalArgumentException("재활용 상자는 판매가 계산에 사용할 수 없습니다");
        }
        return resolved;
    }
}
