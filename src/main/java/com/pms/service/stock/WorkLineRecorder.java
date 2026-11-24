package com.pms.service.stock;

import com.pms.domain.Product;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.StockMovementView;
import com.pms.repository.ProductRepository;
import com.pms.service.PurchaseSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The [저장] of 「출고 작업」 · 「반품 재입고」 · 「교환 발송」 (FEATURE_2611_34 D34 · R4): every line goes through
 * {@link StockLedgerService#record}; a 400 comes back prefixed with the product name. No rule of its own.
 *
 * <p>No {@code @Transactional} on purpose — it joins the caller's transaction, so one rejected line rolls back the
 * whole save.
 *
 * <p>Usage:
 * <pre>{@code
 * workLineRecorder.requireStockLinked();
 * for (Line line : request.lines()) {
 *     workLineRecorder.record(new StockMovementRequest(...));
 * }
 * }</pre>
 *
 * <p>❌ Do not copy the remaining-stock check, the seller derivation or the reason combination here — they stay in
 * {@link StockLedgerServiceImpl} (D34).
 */
@Component
@RequiredArgsConstructor
public class WorkLineRecorder {

    private final StockLedgerService stockLedgerService;
    private final PurchaseSettingService purchaseSettingService;
    private final ProductRepository productRepository;

    /** Rejects a work-screen save while the stock link is off (D4). */
    public void requireStockLinked() {
        if (!purchaseSettingService.isStockLinked()) {
            throw new IllegalArgumentException("재고 연동이 꺼져 있습니다.");
        }
    }

    /** Records one line; a ledger 400 is re-thrown as 「{product name}: {message}」. */
    public StockMovementView record(StockMovementRequest request) {
        try {
            return stockLedgerService.record(request);
        } catch (IllegalArgumentException e) {
            String name = productRepository.findById(request.productId())
                    .map(Product::getProductName)
                    .orElse(String.valueOf(request.productId()));
            throw new IllegalArgumentException(name + ": " + e.getMessage());
        }
    }
}
