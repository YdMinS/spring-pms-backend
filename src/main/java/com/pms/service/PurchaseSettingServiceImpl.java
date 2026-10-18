package com.pms.service;

import com.pms.domain.TenantPurchaseSetting;
import com.pms.dto.request.PurchaseSettingRequest;
import com.pms.dto.response.PurchaseSettingResponse;
import com.pms.repository.TenantPurchaseSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PurchaseSettingService} implementation. No row = {@link #DEFAULT_STOCK_LINKED} (D69). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseSettingServiceImpl implements PurchaseSettingService {

    /** Existing and new tenants both start with the stock link off (D69). */
    public static final boolean DEFAULT_STOCK_LINKED = false;

    private final TenantPurchaseSettingRepository tenantPurchaseSettingRepository;

    @Override
    public PurchaseSettingResponse get() {
        return new PurchaseSettingResponse(isStockLinked());
    }

    @Override
    @Transactional
    public PurchaseSettingResponse update(PurchaseSettingRequest request) {
        boolean stockLinked = request.stockLinked();
        TenantPurchaseSetting next = tenantPurchaseSettingRepository.findFirstByOrderByIdAsc()
                .map(s -> s.toBuilder().stockLinked(stockLinked).build())
                .orElseGet(() -> TenantPurchaseSetting.builder().stockLinked(stockLinked).build());
        tenantPurchaseSettingRepository.save(next);
        return new PurchaseSettingResponse(stockLinked);
    }

    @Override
    public boolean isStockLinked() {
        return tenantPurchaseSettingRepository.findFirstByOrderByIdAsc()
                .map(TenantPurchaseSetting::getStockLinked)
                .orElse(DEFAULT_STOCK_LINKED);
    }
}
