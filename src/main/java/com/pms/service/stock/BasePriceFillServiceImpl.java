package com.pms.service.stock;

import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.StockMovement;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link BasePriceFillService} implementation (FEATURE_2611_17 D22). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BasePriceFillServiceImpl implements BasePriceFillService {

    private final ProductRepository productRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final StockMovementRepository stockMovementRepository;
    private final LotLedgerService lotLedgerService;

    @Override
    @Transactional
    public int fillEmptyPrices(Long productId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null || product.getPrice() == null) {
            return 0;
        }
        List<PurchaseRecord> records = purchaseRecordRepository.findUnpricedPurchases(productId);
        List<StockMovement> lots = stockMovementRepository.findUnpricedLots(productId);
        if (records.isEmpty() && lots.isEmpty()) {
            return 0;
        }
        BigDecimal price = product.getPrice();
        LocalDateTime now = LocalDateTime.now();
        // The amounts are computed by the entities only (FEATURE_2610_19 D135 ① round 32).
        purchaseRecordRepository.saveAll(records.stream().map(r -> r.withBasePriceFill(price, now)).toList());
        stockMovementRepository.saveAll(lots.stream().map(m -> m.withBasePriceFill(price, now)).toList());
        purchaseRecordRepository.flush();
        stockMovementRepository.flush();
        // A filled share re-splits its undecided lot (as 「매입가 넣기」 does), then the debts are rewritten.
        Set<Long> undecidedLots = new LinkedHashSet<>();
        records.stream().filter(r -> r.getSourceMovement() != null)
                .forEach(r -> undecidedLots.add(r.getSourceMovement().getId()));
        undecidedLots.forEach(id -> lotLedgerService.resplit(stockMovementRepository.findById(id).orElseThrow()));
        lotLedgerService.rebuildDebts(productId);
        return records.size() + lots.size();
    }

    @Override
    @Transactional
    public int fillAllEmptyPrices() {
        Set<Long> productIds = new LinkedHashSet<>(stockMovementRepository.findPricedProductIdsWithUnpricedLots());
        productIds.addAll(purchaseRecordRepository.findPricedProductIdsWithUnpricedPurchases());
        int filled = 0;
        for (Long productId : productIds) {
            filled += fillEmptyPrices(productId);
        }
        return filled;
    }
}
