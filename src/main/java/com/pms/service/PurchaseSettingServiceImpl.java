package com.pms.service;

import com.pms.domain.TenantPurchaseSetting;
import com.pms.dto.request.PurchaseSettingRequest;
import com.pms.dto.response.PurchaseSettingResponse;
import com.pms.repository.TenantPurchaseSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    /** FEATURE_2611_22 D11: turning the link on (off or no row -> on) stamps {@code stockLinkedAt}; on -> on keeps it. */
    @Override
    @Transactional
    public PurchaseSettingResponse update(PurchaseSettingRequest request) {
        boolean stockLinked = request.stockLinked();
        LocalDateTime now = LocalDateTime.now();
        TenantPurchaseSetting next = tenantPurchaseSettingRepository.findFirstByOrderByIdAsc()
                .map(s -> s.toBuilder().stockLinked(stockLinked)
                        .stockLinkedAt(stockLinked && !Boolean.TRUE.equals(s.getStockLinked()) ? now : s.getStockLinkedAt())
                        .build())
                .orElseGet(() -> TenantPurchaseSetting.builder().stockLinked(stockLinked)
                        .stockLinkedAt(stockLinked ? now : null).build());
        tenantPurchaseSettingRepository.save(next);
        return new PurchaseSettingResponse(stockLinked);
    }

    @Override
    public boolean isStockLinked() {
        return tenantPurchaseSettingRepository.findFirstByOrderByIdAsc()
                .map(TenantPurchaseSetting::getStockLinked)
                .orElse(DEFAULT_STOCK_LINKED);
    }

    @Override
    public LocalDateTime stockLinkedAt() {
        return tenantPurchaseSettingRepository.findFirstByOrderByIdAsc()
                .map(TenantPurchaseSetting::getStockLinkedAt)
                .orElse(null);
    }
}
