package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.repository.MasterProductCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link MasterChannelConfigService}. See the interface for the resolution rules and the null-check
 * ownership contract.
 */
@Service
@RequiredArgsConstructor
public class MasterChannelConfigServiceImpl implements MasterChannelConfigService {

    private final MasterProductCategoryRepository categoryRepository;

    @Override
    public Category resolveCategory(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            // Transitional guard: a master-less cell cannot be priced/registered.
            throw new IllegalArgumentException("카테고리 미설정");
        }
        return resolveCategory(master.getId(), cell.getPlatform());
    }

    @Override
    public Category resolveCategory(Long masterProductId, String platform) {
        return categoryRepository.findByMasterProductIdAndPlatform(masterProductId, platform)
                .map(mpc -> mpc.getCategory())
                .orElseThrow(() -> new IllegalArgumentException("카테고리 미설정"));
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
