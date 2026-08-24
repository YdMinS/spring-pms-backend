package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.repository.CategoryMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link MasterChannelConfigService}. See the interface for the resolution rules and the null-check
 * ownership contract.
 */
@Service
@RequiredArgsConstructor
public class MasterChannelConfigServiceImpl implements MasterChannelConfigService {

    private final CategoryMappingRepository categoryMappingRepository;

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
    public String resolvePlatformCategoryCode(ProductListing cell) {
        Category standard = resolveStandardCategory(cell);
        return categoryMappingRepository.findByCategoryIdAndPlatform(standard.getId(), cell.getPlatform())
                .map(mapping -> mapping.getPlatformCategoryId())
                .orElseThrow(() -> new IllegalArgumentException(cell.getPlatform() + " 카테고리 매핑 미설정"));
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
        return resolved;
    }
}
