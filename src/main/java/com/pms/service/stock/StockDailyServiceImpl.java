package com.pms.service.stock;

import com.pms.domain.LotShareUsage;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.response.IntakeBarcodeView;
import com.pms.dto.response.StockDailyRowView;
import com.pms.dto.response.StockDailyView;
import com.pms.repository.LotShareUsageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link StockDailyService} implementation (FEATURE_2610_19). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockDailyServiceImpl implements StockDailyService {

    private final StockMovementRepository stockMovementRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final LotShareUsageRepository lotShareUsageRepository;
    private final ProductRepository productRepository;
    private final LotLedgerService lotLedgerService;

    @Override
    public StockDailyView daily(LocalDate date) {
        List<StockMovement> rows = stockMovementRepository.findByMovedOnOrderByIdAsc(date);

        // D130: the owners of the day's undecided intake rows.
        List<Long> undecidedIds = rows.stream().filter(LotLedgerServiceImpl::isUndecided)
                .filter(LotLedgerServiceImpl::isLot).map(StockMovement::getId).toList();
        Map<Long, List<PurchaseRecord>> sharesByLot = new HashMap<>();
        if (!undecidedIds.isEmpty()) {
            for (PurchaseRecord share : purchaseRecordRepository.findBySourceMovementIdIn(undecidedIds)) {
                sharesByLot.computeIfAbsent(share.getSourceMovement().getId(), k -> new ArrayList<>()).add(share);
            }
        }
        // D128: which owner's share each outbound row of an undecided lot used.
        List<Long> outIds = rows.stream().filter(m -> m.getQuantity() < 0).map(StockMovement::getId).toList();
        Map<Long, List<LotShareUsage>> usagesByOut = outIds.isEmpty() ? Map.of()
                : lotShareUsageRepository.findByOutMovementIdIn(outIds).stream()
                .collect(Collectors.groupingBy(u -> u.getOutMovement().getId()));
        // D123 ② · D136 ③: purchase-price amounts of the outgoing rows, per product.
        Map<Long, BigDecimal> outAmounts = new HashMap<>();
        rows.stream().filter(m -> m.getQuantity() < 0)
                .collect(Collectors.groupingBy(m -> m.getProduct().getId(), LinkedHashMap::new,
                        Collectors.mapping(StockMovement::getId, Collectors.toList())))
                .forEach((productId, ids) -> outAmounts.putAll(lotLedgerService.outgoingAmounts(productId, ids)));

        List<StockDailyRowView> views = new ArrayList<>();
        for (StockMovement m : rows) {
            boolean undecided = LotLedgerServiceImpl.isLot(m) && LotLedgerServiceImpl.isUndecided(m);
            List<String> owners = List.of();
            Integer undecidedLeft = null;
            List<StockDailyRowView.NoReceiptShare> noReceiptShares = List.of();
            if (undecided) {
                List<PurchaseRecord> shares = sharesByLot.getOrDefault(m.getId(), List.of());
                Map<String, Integer> byOwner = new LinkedHashMap<>();
                shares.forEach(s -> byOwner.merge(s.getSeller().getSellerName(), s.getQuantity(), Integer::sum));
                owners = byOwner.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
                undecidedLeft = m.getQuantity() - shares.stream().mapToInt(PurchaseRecord::getQuantity).sum();
                // FEATURE_2611_01 D15: each 「영수증 없음」 share is its own 「판매자 바꾸기」 target.
                noReceiptShares = shares.stream().filter(PurchaseRecord::isNoReceiptShare)
                        .map(s -> new StockDailyRowView.NoReceiptShare(s.getId(), s.getSeller().getSellerName(),
                                s.getQuantity()))
                        .toList();
            }
            String orderNumber = null;
            List<String> tags = List.of();
            String sellerName = m.getSeller() == null ? null : m.getSeller().getSellerName();
            if (m.getMovementType() == StockMovementType.STOCK_OUT && m.getOrderLine() != null) {
                Seller orderSeller = m.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
                sellerName = orderSeller.getSellerName();                                    // D124
                orderNumber = m.getOrderLine().getOrder().getExternalOrderId();
                tags = tags(m, orderSeller, usagesByOut.getOrDefault(m.getId(), List.of()));
            }
            views.add(new StockDailyRowView(m.getId(), m.getMovementType(), m.getReason(),
                    m.getProduct().getId(), m.getProduct().getProductName(), m.getQuantity(), sellerName, owners,
                    undecidedLeft, orderNumber, tags, amount(m, outAmounts), m.isSellerChangeableWithoutReceipt(),
                    noReceiptShares));
        }

        // D147: products with an undecided quantity left among that day's purchases.
        List<StockDailyView.UnregisteredProduct> unregistered = new ArrayList<>();
        List<Object[]> sums = purchaseRecordRepository.sumUndecidedOfDay(date);
        Map<Long, Product> products = productRepository.findAllById(
                        sums.stream().map(r -> (Long) r[0]).toList()).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        for (Object[] sum : sums) {
            int left = ((Number) sum[1]).intValue();
            Product product = products.get((Long) sum[0]);
            if (left > 0 && product != null) {
                unregistered.add(new StockDailyView.UnregisteredProduct(product.getId(), product.getProductName(), left));
            }
        }
        unregistered.sort((a, b) -> a.productName().compareTo(b.productName()));
        return new StockDailyView(date, unregistered.size(), unregistered, views);
    }

    @Override
    public IntakeBarcodeView barcode(String barcode) {
        // Same lookup as the packing console (PackingServiceImpl.barcode) — no second rule.
        Product product = productRepository.findByBarcodeId(barcode == null ? "" : barcode.trim()).orElse(null);
        if (product == null) {
            return IntakeBarcodeView.notFound();
        }
        return new IntakeBarcodeView(true, product.getId(), product.getProductName(), product.getPrice());
    }

    /** D124 · D128: whose goods an outbound row used, when they are not the order seller's. */
    private List<String> tags(StockMovement out, Seller orderSeller, List<LotShareUsage> usages) {
        if (out.getSourceMovement() == null) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        if (out.getSeller() != null) {
            if (!Objects.equals(out.getSeller().getId(), orderSeller.getId())) {
                tags.add(out.getSeller().getSellerName() + " 재고 사용");
            }
            return List.copyOf(tags);
        }
        for (LotShareUsage usage : usages) {
            if (usage.getShareRecord() == null) {
                tags.add("영수증 미등록 재고 사용");
            } else if (!Objects.equals(usage.getShareRecord().getSeller().getId(), orderSeller.getId())) {
                tags.add(usage.getShareRecord().getSeller().getSellerName() + " 재고 사용");
            }
        }
        return List.copyOf(tags);
    }

    /**
     * D123: incoming = the row's unit price × quantity; a return into a lot = that lot's unit price × quantity;
     * outgoing = {@link LotLedgerService#outgoingAmounts}. null = 「미기입」.
     */
    private BigDecimal amount(StockMovement m, Map<Long, BigDecimal> outAmounts) {
        if (m.getQuantity() < 0) {
            return outAmounts.get(m.getId());
        }
        BigDecimal unit = m.getSourceMovement() != null ? m.getSourceMovement().getUnitPrice() : m.getUnitPrice();
        return unit == null ? null
                : unit.multiply(BigDecimal.valueOf(m.getQuantity())).setScale(2, RoundingMode.HALF_UP);
    }
}
