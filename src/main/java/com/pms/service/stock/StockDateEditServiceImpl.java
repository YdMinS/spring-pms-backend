package com.pms.service.stock;

import com.pms.domain.PurchaseReceipt;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementDateEdit;
import com.pms.domain.StockMovementType;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.StockMovementDateEditRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link StockDateEditService} implementation (FEATURE_2611_21). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockDateEditServiceImpl implements StockDateEditService {

    static final String RECEIPT_ROW_MESSAGE = "영수증에 묶인 입고는 날짜를 옮길 수 없습니다.";
    static final String RECEIPT_SHARE_MESSAGE = "영수증에 묶인 몫이 있어 날짜를 옮길 수 없습니다.";
    static final String SHARED_RECEIPT_MESSAGE = "같은 영수증에 다른 입고가 함께 있어 날짜를 옮길 수 없습니다.";
    static final String AFTER_TODAY_MESSAGE = "오늘 이후 날짜로는 옮길 수 없습니다.";
    static final String PURCHASE_AFTER_TODAY_MESSAGE = "구매일은 오늘 이후로 정할 수 없습니다.";

    private final StockMovementRepository stockMovementRepository;
    private final StockMovementDateEditRepository stockMovementDateEditRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final PurchaseReceiptRepository purchaseReceiptRepository;
    private final LotLedgerService lotLedgerService;
    private final CostPropagationService costPropagationService;

    @Override
    @Transactional
    public void moveRow(Long movementId, LocalDate newMovedOn) {
        if (movementId == null) {
            throw new IllegalArgumentException("고칠 줄을 고르세요.");
        }
        if (newMovedOn == null) {
            throw new IllegalArgumentException("고칠 내용을 보내세요.");
        }
        StockMovement row = stockMovementRepository.findById(movementId)
                .orElseThrow(() -> new ResourceNotFoundException("StockMovement", movementId));
        if (newMovedOn.equals(row.getMovedOn())) {
            return;                                                          // D9: the same day changes nothing
        }
        boolean orderOutbound = row.getMovementType() == StockMovementType.STOCK_OUT && row.getOrderLine() != null;
        List<StockMovement> rows = orderOutbound ? orderRowsOfDay(row) : List.of(row);

        // D12 · D13 · D25: the purchase records and 「영수증 나중에」 receipts that follow the row, checked first.
        List<PurchaseRecord> records = new ArrayList<>();
        Map<Long, PurchaseReceipt> laterReceipts = new LinkedHashMap<>();
        PurchaseRecord record = row.getPurchaseRecord();
        if (LotLedgerServiceImpl.isLot(row) && LotLedgerServiceImpl.isUndecided(row)) {
            List<PurchaseRecord> shares = purchaseRecordRepository.findBySourceMovementIdIn(List.of(row.getId()));
            if (shares.stream().anyMatch(s -> s.receiptKind() == PurchaseRecord.ReceiptKind.RECEIPT)) {
                throw new IllegalArgumentException(RECEIPT_SHARE_MESSAGE);
            }
            shares.stream().filter(s -> s.receiptKind() == PurchaseRecord.ReceiptKind.RECEIPT_LATER)
                    .forEach(s -> laterReceipts.putIfAbsent(s.getReceipt().getId(), s.getReceipt()));
            requireAlone(laterReceipts.values(), shares.stream().map(PurchaseRecord::getId).collect(Collectors.toSet()));
            if (record != null) {
                records.add(record);
                records.addAll(shares);
                records.addAll(minusesOf(record, shares));
            }
        } else if (record != null) {
            if (record.receiptKind() == PurchaseRecord.ReceiptKind.RECEIPT) {
                throw new IllegalArgumentException(RECEIPT_ROW_MESSAGE);
            }
            if (record.receiptKind() == PurchaseRecord.ReceiptKind.RECEIPT_LATER) {
                laterReceipts.put(record.getReceipt().getId(), record.getReceipt());
                requireAlone(laterReceipts.values(), Set.of(record.getId()));
            }
            records.add(record);
        }
        checkLimits(rows, newMovedOn);

        Map<Long, Long> before = records.isEmpty() ? Map.of() : latestMovers(List.of(row.getProduct().getId()));
        List<StockMovement> moved = moveRows(rows, newMovedOn);
        purchaseRecordRepository.saveAll(records.stream().map(r -> r.toBuilder().purchasedOn(newMovedOn).build())
                .toList());
        purchaseReceiptRepository.saveAll(laterReceipts.values().stream()
                .map(r -> r.toBuilder().purchasedOn(newMovedOn).build()).toList());
        stockMovementRepository.flush();
        purchaseRecordRepository.flush();
        if (orderOutbound) {
            // D6: the same order as right after an outbound — re-split the undecided lots used, then the debts.
            moved.stream().collect(Collectors.groupingBy(m -> m.getProduct().getId(), LinkedHashMap::new,
                    Collectors.toList())).forEach(lotLedgerService::afterOutbound);
        }
        rebasePrices(before);
    }

    @Override
    @Transactional
    public void moveReceiptDay(List<PurchaseRecord> lines, LocalDate newDay) {
        if (newDay.isAfter(LocalDate.now(SyncWindow.KST))) {
            throw new IllegalArgumentException(PURCHASE_AFTER_TODAY_MESSAGE);               // D32 · D33
        }
        if (lines.isEmpty()) {
            return;
        }
        Map<Long, List<StockMovement>> rowsByRecord = stockMovementRepository
                .findByPurchaseRecordIdIn(lines.stream().map(PurchaseRecord::getId).toList()).stream()
                .filter(m -> !newDay.equals(m.getMovedOn()))
                .collect(Collectors.groupingBy(m -> m.getPurchaseRecord().getId()));
        List<StockMovement> rows = new ArrayList<>();
        lines.forEach(line -> rows.addAll(rowsByRecord.getOrDefault(line.getId(), List.of())));
        // D31: every blocked product at once, receipt line order, the earliest day per product.
        Map<Long, LocalDate> firstOut = firstOutDays(rows);
        Map<String, LocalDate> blocked = new LinkedHashMap<>();
        for (PurchaseRecord line : lines) {
            for (StockMovement m : rowsByRecord.getOrDefault(line.getId(), List.of())) {
                LocalDate first = firstOut.get(m.getId());
                if (first != null && newDay.isAfter(first)) {
                    blocked.merge(line.getProduct().getProductName(), first, (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }
        if (!blocked.isEmpty()) {
            throw new IllegalArgumentException(blocked.entrySet().stream()
                    .map(e -> e.getKey() + ": " + firstOutMessage(e.getValue()))
                    .collect(Collectors.joining("\n")));
        }
        Map<Long, Long> before = latestMovers(lines.stream().map(r -> r.getProduct().getId()).distinct().toList());
        purchaseRecordRepository.saveAll(lines.stream().map(r -> r.toBuilder().purchasedOn(newDay).build()).toList());
        moveRows(rows, newDay);
        stockMovementRepository.flush();
        purchaseRecordRepository.flush();
        rebasePrices(before);
    }

    /** D4: every outbound row of the order of {@code row} on its day, record order. */
    private List<StockMovement> orderRowsOfDay(StockMovement row) {
        Long orderId = row.getOrderLine().getOrder().getId();
        return stockMovementRepository.findByMovedOnOrderByIdAsc(row.getMovedOn()).stream()
                .filter(m -> m.getMovementType() == StockMovementType.STOCK_OUT && m.getOrderLine() != null
                        && orderId.equals(m.getOrderLine().getOrder().getId()))
                .toList();
    }

    /**
     * D13: the minus rows the assignments of {@code shares} added to the undecided record. A minus row has no link to
     * its share, so each minus row of that product and purchase day pairs with the latest share before it (record
     * order) of the same quantity that is not paired yet — an assignment saves the share, then its minus row.
     */
    private List<PurchaseRecord> minusesOf(PurchaseRecord undecided, List<PurchaseRecord> shares) {
        Long productId = undecided.getProduct().getId();
        LocalDate day = undecided.getPurchasedOn();
        List<PurchaseRecord> dayShares = purchaseRecordRepository.findSharesOfUndecidedDay(productId, day);
        Map<Long, PurchaseRecord> minusByShare = new HashMap<>();
        for (PurchaseRecord minus : purchaseRecordRepository.findAssignmentMinuses(productId, day)) {
            PurchaseRecord match = null;
            for (PurchaseRecord share : dayShares) {
                if (share.getId() < minus.getId() && !minusByShare.containsKey(share.getId())
                        && share.getQuantity() + minus.getQuantity() == 0) {
                    match = share;
                }
            }
            if (match != null) {
                minusByShare.put(match.getId(), minus);
            }
        }
        return shares.stream().map(s -> minusByShare.get(s.getId())).filter(m -> m != null).toList();
    }

    /** D25: a 「영수증 나중에」 receipt moves only when every purchase on it moves with the row. */
    private void requireAlone(Collection<PurchaseReceipt> receipts, Set<Long> moving) {
        if (receipts.isEmpty()) {
            return;
        }
        List<PurchaseRecord> onReceipts = purchaseRecordRepository
                .findByReceiptIds(receipts.stream().map(PurchaseReceipt::getId).toList());
        if (onReceipts.stream().anyMatch(r -> !moving.contains(r.getId()))) {
            throw new IllegalArgumentException(SHARED_RECEIPT_MESSAGE);
        }
    }

    /** D5 · D27 · D33 · D34: today (KST) → the order day → the lot's day → the first outgoing day, rows in order. */
    private void checkLimits(List<StockMovement> rows, LocalDate newDay) {
        if (newDay.isAfter(LocalDate.now(SyncWindow.KST))) {
            throw new IllegalArgumentException(AFTER_TODAY_MESSAGE);
        }
        for (StockMovement m : rows) {
            LocalDateTime orderedAt = m.getOrderLine() == null ? null : m.getOrderLine().getOrder().getOrderedAt();
            if (orderedAt != null && newDay.isBefore(orderedAt.toLocalDate())) {
                throw new IllegalArgumentException("주문일(" + monthDay(orderedAt.toLocalDate())
                        + ")보다 앞으로 옮길 수 없습니다.");
            }
        }
        for (StockMovement m : rows) {
            StockMovement lot = m.getQuantity() < 0 ? m.getSourceMovement() : null;
            if (lot != null && newDay.isBefore(lot.getMovedOn())) {
                throw new IllegalArgumentException(monthDay(lot.getMovedOn())
                        + " 입고에서 나간 줄이라 그보다 앞으로 옮길 수 없습니다.");
            }
        }
        Map<Long, LocalDate> firstOut = firstOutDays(rows.stream().filter(m -> m.getQuantity() > 0).toList());
        for (StockMovement m : rows) {
            LocalDate first = firstOut.get(m.getId());
            if (first != null && newDay.isAfter(first)) {
                throw new IllegalArgumentException(firstOutMessage(first));
            }
        }
    }

    /** D27: the earliest day of the outgoing rows (order outbound · disposal · adjust −) of each lot. */
    private Map<Long, LocalDate> firstOutDays(Collection<StockMovement> lots) {
        Map<Long, LocalDate> first = new HashMap<>();
        if (lots.isEmpty()) {
            return first;
        }
        for (StockMovement out : stockMovementRepository
                .findBySourceMovementIdIn(lots.stream().map(StockMovement::getId).toList())) {
            if (out.getQuantity() < 0) {
                first.merge(out.getSourceMovement().getId(), out.getMovedOn(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return first;
    }

    /** D7 · D24: each row takes the new day and one correction row (KST wall clock, the login email). */
    private List<StockMovement> moveRows(List<StockMovement> rows, LocalDate newDay) {
        LocalDateTime at = LocalDateTime.now(SyncWindow.KST);
        String by = currentUsername();
        List<StockMovementDateEdit> edits = new ArrayList<>();
        List<StockMovement> moved = new ArrayList<>();
        for (StockMovement m : rows) {
            edits.add(StockMovementDateEdit.builder().stockMovement(m).oldMovedOn(m.getMovedOn()).newMovedOn(newDay)
                    .editedBy(by).editedAt(at).build());
            moved.add(m.toBuilder().movedOn(newDay).build());
        }
        stockMovementDateEditRepository.saveAll(edits);
        return stockMovementRepository.saveAll(moved);
    }

    /** D28: the latest purchase that moves the registered price, per product (null = none). */
    private Map<Long, Long> latestMovers(Collection<Long> productIds) {
        Map<Long, Long> latest = new LinkedHashMap<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            List<PurchaseRecord> movers = purchaseRecordRepository.findBasePriceMovers(productId);
            latest.put(productId, movers.isEmpty() ? null : movers.get(0).getId());
        }
        return latest;
    }

    /** D28 · D29: a new latest mover sets the registered price (history PURCHASE_UPDATE); the same one changes nothing. */
    private void rebasePrices(Map<Long, Long> before) {
        before.forEach((productId, beforeId) -> {
            List<PurchaseRecord> movers = purchaseRecordRepository.findBasePriceMovers(productId);
            if (!movers.isEmpty() && !movers.get(0).getId().equals(beforeId)) {
                costPropagationService.updateBasePrice(movers.get(0));
            }
        });
    }

    private static String firstOutMessage(LocalDate day) {
        return "이 입고에서 " + monthDay(day) + "에 나간 기록이 있어 그보다 뒤로 옮길 수 없습니다.";
    }

    /** 「11월 3일」 — the 「○월 ○일」 of D34. */
    private static String monthDay(LocalDate day) {
        return day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
    }

    /** Same helper as {@code PurchaseReceiptServiceImpl.currentUsername()}. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
