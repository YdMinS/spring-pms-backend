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
        // D123 ② · D136 ③: purchase-price amounts of the outgoing rows, per product, with their unit prices
        // (FEATURE_2611_17 D16 · D22).
        Map<Long, LotLedgerService.OutgoingPrice> outPrices = new HashMap<>();
        rows.stream().filter(m -> m.getQuantity() < 0)
                .collect(Collectors.groupingBy(m -> m.getProduct().getId(), LinkedHashMap::new,
                        Collectors.mapping(StockMovement::getId, Collectors.toList())))
                .forEach((productId, ids) -> outPrices.putAll(lotLedgerService.outgoingPrices(productId, ids)));

        List<StockDailyRowView> views = new ArrayList<>();
        for (StockMovement m : rows) {
            boolean undecided = LotLedgerServiceImpl.isLot(m) && LotLedgerServiceImpl.isUndecided(m);
            List<String> owners = List.of();
            Integer undecidedLeft = null;
            List<StockDailyRowView.Share> shareViews = List.of();
            Priced priced = priced(m, outPrices);
            BigDecimal amount = priced.amount();
            if (undecided) {
                List<PurchaseRecord> shares = sharesByLot.getOrDefault(m.getId(), List.of());
                Map<String, Integer> byOwner = new LinkedHashMap<>();
                shares.forEach(s -> byOwner.merge(s.getSeller().getSellerName(), s.getQuantity(), Integer::sum));
                owners = byOwner.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
                undecidedLeft = m.getQuantity() - shares.stream().mapToInt(PurchaseRecord::getQuantity).sum();
                // FEATURE_2611_16 D8: every share is a line of its own (a 「매입가 넣기」 target); the 「영수증 없음」 ones
                // are also 「판매자 바꾸기」 targets (FEATURE_2611_01 D15).
                shareViews = shares.stream()
                        .map(s -> new StockDailyRowView.Share(s.getId(), s.getSeller().getId(),
                                s.getSeller().getSellerName(), s.getQuantity(), s.getTotalAmount(), s.receiptKind(),
                                s.getReceipt() == null ? null : s.getReceipt().getId(), s.isNoReceiptShare(),
                                s.getUnitPrice(), s.getUnitPrice() != null && s.isPriceFromBase()))
                        .toList();
                amount = undecidedAmount(m, shares, undecidedLeft);
                priced = undecidedPriced(m, shares, undecidedLeft, amount);
            }
            String orderNumber = null;
            List<String> tags = List.of();
            Seller rowSeller = m.getSeller();
            if (m.getMovementType() == StockMovementType.STOCK_OUT && m.getOrderLine() != null) {
                Seller orderSeller = m.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
                rowSeller = orderSeller;                                                     // D124
                orderNumber = m.getOrderLine().getOrder().getExternalOrderId();
                tags = tags(m, orderSeller, usagesByOut.getOrDefault(m.getId(), List.of()));
            }
            // FEATURE_2611_16 D1 · D17: a decided purchase row is a 「매입가 넣기」 target through its record.
            PurchaseRecord record = m.isPriceRetypable() ? m.getPurchaseRecord() : null;
            views.add(new StockDailyRowView(m.getId(), m.getMovementType(), m.getReason(),
                    m.getProduct().getId(), m.getProduct().getProductName(), m.getProduct().getImageUrl(),
                    m.getQuantity(), rowSeller == null ? null : rowSeller.getId(),
                    rowSeller == null ? null : rowSeller.getSellerName(), owners,
                    undecidedLeft, orderNumber, tags, amount, m.isSellerChangeableWithoutReceipt(),
                    record == null ? null : record.getId(), record == null ? null : record.receiptKind(),
                    record == null || record.getReceipt() == null ? null : record.getReceipt().getId(),
                    shareViews, priced.unitPrices(), priced.priceFromBase(), m.isOpeningPriceEditable()));
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
        return new IntakeBarcodeView(true, product.getId(), product.getProductName(), product.getPrice(),
                product.getImageUrl());
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
     * FEATURE_2611_16 D8: an undecided intake row = its shares' totals + what is left × the undecided unit price. A part
     * without an amount is left out; every part without one → null (「미기입」), as the receipt list sums its lines
     * (FEATURE_2610_18 D94).
     */
    private static BigDecimal undecidedAmount(StockMovement lot, List<PurchaseRecord> shares, int left) {
        List<BigDecimal> parts = new ArrayList<>();
        shares.stream().map(PurchaseRecord::getTotalAmount).filter(Objects::nonNull).forEach(parts::add);
        if (left > 0 && lot.getUnitPrice() != null) {
            parts.add(PurchaseRecord.totalOf(lot.getUnitPrice(), left));
        }
        return parts.isEmpty() ? null : parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * FEATURE_2611_17 D16 · D22: the unit prices behind an undecided intake row's amount — the shares with an amount,
     * then what is left × the undecided unit price — exactly the parts {@link #undecidedAmount} adds up.
     */
    private static Priced undecidedPriced(StockMovement lot, List<PurchaseRecord> shares, int left, BigDecimal amount) {
        if (amount == null) {
            return Priced.UNKNOWN;
        }
        List<StockDailyRowView.UnitCount> parts = new ArrayList<>();
        boolean fromBase = false;
        for (PurchaseRecord share : shares) {
            if (share.getTotalAmount() == null || share.getUnitPrice() == null) continue;
            parts.add(new StockDailyRowView.UnitCount(share.getUnitPrice(), share.getQuantity()));
            fromBase |= share.isPriceFromBase();
        }
        if (left > 0 && lot.getUnitPrice() != null) {
            parts.add(new StockDailyRowView.UnitCount(lot.getUnitPrice(), left));
            fromBase |= lot.isPriceFromBase();
        }
        return new Priced(amount, StockDailyRowView.UnitCount.merge(parts), fromBase);
    }

    /**
     * D123: incoming = the row's unit price × quantity; a return into a lot = that lot's unit price × quantity;
     * outgoing = {@link LotLedgerService#outgoingPrices}. null = 「미기입」. An undecided intake row is
     * {@link #undecidedAmount} instead (FEATURE_2611_16 D8). FEATURE_2611_17 D16 · D22: with the unit prices behind
     * the amount and the 「미기입」 mark of a price filled from the registered price.
     */
    private Priced priced(StockMovement m, Map<Long, LotLedgerService.OutgoingPrice> outPrices) {
        if (m.getQuantity() < 0) {
            LotLedgerService.OutgoingPrice out = outPrices.getOrDefault(m.getId(), LotLedgerService.OutgoingPrice.UNKNOWN);
            return new Priced(out.amount(), out.unitPrices(), out.priceFromBase());
        }
        StockMovement lot = m.getSourceMovement() != null ? m.getSourceMovement() : m;
        BigDecimal unit = lot.getUnitPrice();
        if (unit == null) {
            return Priced.UNKNOWN;
        }
        return new Priced(PurchaseRecord.totalOf(unit, m.getQuantity()),
                List.of(new StockDailyRowView.UnitCount(unit, m.getQuantity())),
                lot.isPriceFromBase());
    }

    /** One row's amount, the unit prices behind it (null with a null amount) and its 「미기입」 mark. */
    private record Priced(BigDecimal amount, List<StockDailyRowView.UnitCount> unitPrices, boolean priceFromBase) {
        static final Priced UNKNOWN = new Priced(null, null, false);
    }
}
