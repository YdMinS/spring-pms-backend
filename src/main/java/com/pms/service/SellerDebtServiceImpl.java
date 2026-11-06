package com.pms.service;

import com.pms.domain.PriceChangeReason;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerDebt;
import com.pms.domain.SellerSettlement;
import com.pms.domain.StockMovement;
import com.pms.dto.request.DebtUnitPriceRequest;
import com.pms.dto.request.SettlementCreateRequest;
import com.pms.dto.response.SellerDebtEntryView;
import com.pms.dto.response.SellerDebtPairView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.SellerSettlementRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.price.PriceHistoryRecorder;
import com.pms.service.stock.LotLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SellerDebtService} implementation (FEATURE_2610_19).
 *
 * <p>Class default readOnly, write methods override with @Transactional.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerDebtServiceImpl implements SellerDebtService {

    private static final String KIND_BORROW = "BORROW";
    private static final String KIND_SETTLEMENT = "SETTLEMENT";

    private final SellerDebtRepository sellerDebtRepository;
    private final SellerSettlementRepository sellerSettlementRepository;
    private final SellerRepository sellerRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final CostPropagationService costPropagationService;
    private final PriceHistoryRecorder priceHistoryRecorder;
    private final LotLedgerService lotLedgerService;

    @Override
    public List<SellerDebtPairView> pairs() {
        Map<String, PairSum> sums = new LinkedHashMap<>();
        for (SellerDebt debt : sellerDebtRepository.findAllWithSellers()) {
            PairSum sum = pair(sums, debt.getBorrower(), debt.getLender());
            if (debt.getAmount() == null) {
                sum.unknown++;                                       // D137: never counted as 0
            } else {
                sum.add(debt.getBorrower(), debt.getAmount());       // the borrower owes the lender
            }
        }
        for (SellerSettlement settlement : sellerSettlementRepository.findAllWithSellers()) {
            if (settlement.getCancelledAt() != null) continue;      // D90: a cancelled settlement is left out
            PairSum sum = pair(sums, settlement.getPayer(), settlement.getReceiver());
            sum.add(settlement.getPayer(), settlement.getAmount().negate());
        }
        List<SellerDebtPairView> views = new ArrayList<>();
        for (PairSum sum : sums.values()) {
            int sign = sum.aOwesB.signum();
            if (sign == 0 && sum.unknown == 0) continue;            // D51 · D137
            Long debtor = sign > 0 ? sum.a.getId() : (sign < 0 ? sum.b.getId() : null);
            views.add(new SellerDebtPairView(sum.a.getId(), sum.a.getSellerName(), sum.b.getId(),
                    sum.b.getSellerName(), debtor, sum.aOwesB.abs(), sum.unknown));
        }
        views.sort(Comparator.comparing(SellerDebtPairView::balance, Comparator.reverseOrder())
                .thenComparing(SellerDebtPairView::sellerAId).thenComparing(SellerDebtPairView::sellerBId));
        return views;
    }

    @Override
    public List<SellerDebtEntryView> entries(Long sellerAId, Long sellerBId) {
        List<SellerDebtEntryView> entries = new ArrayList<>();
        for (SellerDebt debt : sellerDebtRepository.findBetween(sellerAId, sellerBId)) {
            Priced target = target(debt);
            boolean canFill = debt.getAmount() == null && target.unitPrice() == null;
            entries.add(new SellerDebtEntryView(KIND_BORROW, debt.getId(), debt.getMovedOn(),
                    debt.getBorrower().getId(), debt.getBorrower().getSellerName(),
                    debt.getLender().getId(), debt.getLender().getSellerName(),
                    debt.getOutMovement().getOrderLine().getOrder().getExternalOrderId(),
                    debt.getProduct().getProductName(), debt.getQuantity(), debt.getUnitPrice(), debt.getAmount(),
                    canFill, target.filledAt() != null, null, false));
        }
        for (SellerSettlement s : sellerSettlementRepository.findBetween(sellerAId, sellerBId)) {
            entries.add(new SellerDebtEntryView(KIND_SETTLEMENT, s.getId(), s.getSettledOn(),
                    s.getPayer().getId(), s.getPayer().getSellerName(),
                    s.getReceiver().getId(), s.getReceiver().getSellerName(),
                    null, null, null, null, s.getAmount(), false, false, s.getMemo(), s.getCancelledAt() != null));
        }
        entries.sort(Comparator.comparing(SellerDebtEntryView::date, Comparator.reverseOrder())
                .thenComparing(SellerDebtEntryView::kind)
                .thenComparing(SellerDebtEntryView::id, Comparator.reverseOrder()));
        return entries;
    }

    @Override
    @Transactional
    public void settle(SettlementCreateRequest request) {
        if (request.payerSellerId().equals(request.receiverSellerId())) {
            throw new IllegalArgumentException("서로 다른 두 판매자를 고르세요.");
        }
        Seller payer = seller(request.payerSellerId());
        Seller receiver = seller(request.receiverSellerId());
        sellerSettlementRepository.save(SellerSettlement.builder()
                .payer(payer).receiver(receiver).settledOn(request.settledOn())
                .amount(request.amount())
                .memo(request.memo() == null || request.memo().isBlank() ? null : request.memo().trim())
                .createdBy(currentUsername())
                .build());
    }

    @Override
    @Transactional
    public void cancelSettlement(Long settlementId) {
        SellerSettlement settlement = sellerSettlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("SellerSettlement", settlementId));
        if (settlement.getCancelledAt() != null) {
            throw new IllegalArgumentException("이미 취소된 기록입니다.");
        }
        sellerSettlementRepository.save(settlement.toBuilder().cancelledAt(LocalDateTime.now()).build());
    }

    @Override
    @Transactional
    public void fillUnitPrice(Long debtId, DebtUnitPriceRequest request) {
        SellerDebt debt = sellerDebtRepository.findById(debtId)
                .orElseThrow(() -> new ResourceNotFoundException("SellerDebt", debtId));
        Priced target = target(debt);
        boolean editing = target.unitPrice() != null;
        if (editing && target.filledAt() == null) {
            throw new IllegalArgumentException("이 단가는 여기서 고칠 수 없습니다.");   // D143 ②
        }
        LocalDateTime now = LocalDateTime.now();
        Long productId = debt.getProduct().getId();

        if (target.record() != null) {
            // D141 ① ②: the purchase record (total = unit × quantity) and every stock row that copies its price.
            PurchaseRecord record = target.record();
            boolean latest = isLatestMover(record);
            // D144 ② ③ reflect · D143 ① mark; the amounts are computed by PurchaseRecord only (D135 ① round 32).
            PurchaseRecord saved = purchaseRecordRepository.save(
                    record.withTypedUnitPrice(request.unitPrice(), latest, now));
            List<StockMovement> rows = stockMovementRepository.findByPurchaseRecordIdIn(List.of(record.getId()));
            stockMovementRepository.saveAll(rows.stream()
                    .map(m -> m.toBuilder().unitPrice(saved.getUnitPrice()).build()).toList());
            if (latest) {
                costPropagationService.updateBasePrice(saved);   // D144 ② ⑤ — overwrites, history PURCHASE_UPDATE
            }
            if (record.getSourceMovement() != null) {
                stockMovementRepository.flush();
                lotLedgerService.resplit(record.getSourceMovement());   // D141 ④ (a)
            }
        } else {
            // D141 ① · D145 · D146: a lot without a purchase record (opening stock, a return's new lot).
            StockMovement lot = target.lot();
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
            BigDecimal registered = product.getPrice();
            boolean setRegistered;
            if (registered == null) {
                setRegistered = true;                                                     // D145 · D146 ② (a)
            } else if (editing && lot.getBasePriceFilledAt() != null) {
                setRegistered = registered.compareTo(PurchaseRecord.registeredPriceOf(lot.getUnitPrice())) == 0;
            } else {
                setRegistered = false;
            }
            BigDecimal unit = PurchaseRecord.typedUnit(request.unitPrice());
            stockMovementRepository.save(lot.toBuilder()
                    .unitPrice(unit)
                    .priceFilledAt(now)
                    .basePriceFilledAt(setRegistered ? now : null)   // D146 ① · ② (c) clears it
                    .build());
            if (setRegistered) {
                BigDecimal newPrice = PurchaseRecord.registeredPriceOf(unit);
                productRepository.save(product.toBuilder().price(newPrice).build());
                priceHistoryRecorder.recordProductCost(product, registered, newPrice,
                        PriceChangeReason.PURCHASE_UPDATE, null);    // D145 ② · D146 ③
            }
        }
        stockMovementRepository.flush();
        lotLedgerService.rebuildDebts(productId);                    // D141 ④ (b)
    }

    // --- helpers ---

    /**
     * D144 ①: no other purchase of the product that moves the registered price is later (purchase day, then record
     * order). The seller does not matter.
     */
    private boolean isLatestMover(PurchaseRecord record) {
        // The rule lives in PurchaseRecord — 「매입가 넣기」 uses the same one (FEATURE_2611_16 D3).
        return PurchaseRecord.isLatestMover(record,
                purchaseRecordRepository.findBasePriceMovers(record.getProduct().getId()));
    }

    /** D141 ①: the lot behind a debt — the share's record, the lot's record, or the lot itself. */
    private Priced target(SellerDebt debt) {
        if (debt.getShareRecord() != null) {
            PurchaseRecord share = debt.getShareRecord();
            return new Priced(share, null, share.getUnitPrice(), share.getPriceFilledAt());
        }
        StockMovement lot = debt.getSourceMovement();
        if (lot.getPurchaseRecord() != null) {
            PurchaseRecord record = lot.getPurchaseRecord();
            return new Priced(record, null, record.getUnitPrice(), record.getPriceFilledAt());
        }
        return new Priced(null, lot, lot.getUnitPrice(), lot.getPriceFilledAt());
    }

    private record Priced(PurchaseRecord record, StockMovement lot, BigDecimal unitPrice, LocalDateTime filledAt) {
    }

    private PairSum pair(Map<String, PairSum> sums, Seller x, Seller y) {
        Seller a = x.getId() < y.getId() ? x : y;
        Seller b = a == x ? y : x;
        return sums.computeIfAbsent(a.getId() + ":" + b.getId(), k -> new PairSum(a, b));
    }

    /** Running balance of one pair: positive = sellerA owes sellerB. */
    private static final class PairSum {
        final Seller a;
        final Seller b;
        BigDecimal aOwesB = BigDecimal.ZERO;
        int unknown;

        PairSum(Seller a, Seller b) {
            this.a = a;
            this.b = b;
        }

        /** {@code amount} owed by {@code owes} to the other seller of the pair. */
        void add(Seller owes, BigDecimal amount) {
            aOwesB = owes.getId().equals(a.getId()) ? aOwesB.add(amount) : aOwesB.subtract(amount);
        }
    }

    private Seller seller(Long id) {
        return sellerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Seller", id));
    }

    /** Same helper as {@code PurchaseReceiptServiceImpl.currentUsername()}. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
