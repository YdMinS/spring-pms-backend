package com.pms.service.stock;

import com.pms.domain.LotShareUsage;
import com.pms.domain.OrderLine;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.request.StockFillRequest;
import com.pms.dto.response.FillCandidateView;
import com.pms.dto.response.FillLotView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.LotShareUsageRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.PurchaseSettingService;
import com.pms.service.cost.CostBasisResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link StockFillService} implementation (FEATURE_2611_22). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockFillServiceImpl implements StockFillService {

    private final StockMovementRepository stockMovementRepository;
    private final LotShareUsageRepository lotShareUsageRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderLineExpander orderLineExpander;
    private final CostBasisResolver costBasisResolver;
    private final LotLedgerService lotLedgerService;
    private final PurchaseSettingService purchaseSettingService;

    @Override
    public List<FillCandidateView> candidates(List<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty() || !purchaseSettingService.isStockLinked()) {
            return List.of();
        }
        LocalDateTime linkedAt = purchaseSettingService.stockLinkedAt();
        Map<Long, StockMovement> byId = stockMovementRepository.findAllById(
                        lotIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(StockMovement::getId, m -> m));
        Map<Long, List<StockMovement>> lotsByProduct = new LinkedHashMap<>();
        for (Long id : lotIds) {
            StockMovement lot = id == null ? null : byId.get(id);
            if (lot == null || !lot.isFillLot()) continue;
            List<StockMovement> group = lotsByProduct.computeIfAbsent(lot.getProduct().getId(), k -> new ArrayList<>());
            if (!group.contains(lot)) group.add(lot);
        }
        List<FillCandidateView> views = new ArrayList<>();
        lotsByProduct.forEach((productId, lots) -> {
            List<StockMovement> rows = stockMovementRepository.findEmptyOutbound(productId).stream()
                    .filter(m -> m.isFillableOutbound(linkedAt)).toList();
            if (rows.isEmpty()) return;
            Map<Long, Integer> left = lotLedgerService.fillLots(productId).stream()
                    .collect(Collectors.toMap(l -> l.lot().getId(), LotLedgerService.LotLeft::left));
            // D32: what an earlier lot of this save checked is no longer there for the next one.
            Map<Long, Integer> open = new HashMap<>();
            rows.forEach(m -> open.put(m.getId(), -m.getQuantity()));
            List<FillCandidateView.Lot> lotViews = new ArrayList<>();
            for (StockMovement lot : lots) {
                Integer lotLeft = left.get(lot.getId());
                if (lotLeft == null) continue;
                int budget = lotLeft;
                List<FillCandidateView.Row> rowViews = new ArrayList<>();
                for (StockMovement row : fillOrder(rows, lot)) {
                    // D23: checked at first in D17 order until the lot's quantity is used up.
                    int checked = Math.min(budget, open.get(row.getId()));
                    budget -= checked;
                    open.merge(row.getId(), -checked, Integer::sum);
                    rowViews.add(new FillCandidateView.Row(row.getId(),
                            row.getOrderLine().getOrder().getExternalOrderId(), row.getMovedOn(),
                            orderSeller(row).getSellerName(), -row.getQuantity(), checked));
                }
                lotViews.add(new FillCandidateView.Lot(lot.getId(), lot.getMovedOn(), sellerName(lot), lotLeft,
                        rowViews));
            }
            if (lotViews.isEmpty()) return;
            views.add(new FillCandidateView(productId, lots.get(0).getProduct().getProductName(), rows.size(),
                    rows.stream().mapToInt(m -> -m.getQuantity()).sum(), lotViews));
        });
        return views;
    }

    @Override
    public List<FillLotView> lots(Long movementId) {
        StockMovement row = fillableRow(movementId, linkedAtOrThrow());
        return lotLedgerService.fillLots(row.getProduct().getId()).stream()
                .map(l -> new FillLotView(l.lot().getId(), l.lot().getMovedOn(), sellerName(l.lot()), l.left()))
                .toList();
    }

    @Override
    @Transactional
    public void fill(StockFillRequest request) {
        LocalDateTime linkedAt = linkedAtOrThrow();
        if (request == null || request.fills() == null || request.fills().isEmpty()
                || request.fills().stream().anyMatch(f -> f == null || f.lines() == null || f.lines().isEmpty())) {
            throw new IllegalArgumentException("채울 출고를 고르세요.");
        }
        if (request.fills().stream().flatMap(f -> f.lines().stream())
                .anyMatch(l -> l == null || l.quantity() == null || l.quantity() < 1)) {
            throw new IllegalArgumentException("수량은 0보다 커야 합니다");
        }
        // Every check before any write: the lots, the rows, then the counts (D15 · D23).
        Map<Long, StockMovement> lots = new LinkedHashMap<>();
        Map<Long, StockMovement> rows = new LinkedHashMap<>();
        for (StockFillRequest.Fill fill : request.fills()) {
            StockMovement lot = stockMovementRepository.findById(fill.lotId())
                    .orElseThrow(() -> new ResourceNotFoundException("StockMovement", fill.lotId()));
            if (!lot.isFillLot()) {
                throw new IllegalArgumentException("채울 수 없는 입고입니다.");
            }
            lots.put(lot.getId(), lot);
            for (StockFillRequest.Line line : fill.lines()) {
                StockMovement row = rows.containsKey(line.movementId()) ? rows.get(line.movementId())
                        : fillableRow(line.movementId(), linkedAt);
                if (!row.getProduct().getId().equals(lot.getProduct().getId())) {
                    throw new IllegalArgumentException("채울 수 없는 줄입니다.");
                }
                rows.put(row.getId(), row);
            }
        }
        Map<Long, Integer> left = new HashMap<>();
        lots.values().stream().map(l -> l.getProduct().getId()).distinct()
                .forEach(productId -> lotLedgerService.fillLots(productId)
                        .forEach(l -> left.put(l.lot().getId(), l.left())));
        Map<Long, Integer> byLot = new LinkedHashMap<>();
        Map<Long, Integer> byRow = new LinkedHashMap<>();
        for (StockFillRequest.Fill fill : request.fills()) {
            for (StockFillRequest.Line line : fill.lines()) {
                byLot.merge(fill.lotId(), line.quantity(), Integer::sum);
                byRow.merge(line.movementId(), line.quantity(), Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> entry : byLot.entrySet()) {
            int lotLeft = left.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > lotLeft) {
                throw new IllegalArgumentException("남은 입고 수량(" + lotLeft + "개)보다 많습니다.");
            }
        }
        for (Map.Entry<Long, Integer> entry : byRow.entrySet()) {
            int empty = -rows.get(entry.getKey()).getQuantity();
            if (entry.getValue() > empty) {
                throw new IllegalArgumentException("빈 출고 수량(" + empty + "개)보다 많습니다.");
            }
        }

        // D8 · D9 · D124: the row takes the filled pieces, the lot and the lot owner; what is still empty is a new row.
        LocalDateTime now = LocalDateTime.now();
        String user = currentUsername();
        Map<Long, StockMovement> stillEmpty = new HashMap<>(rows);
        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> undecidedLots = new LinkedHashSet<>();
        Set<Long> lineIds = new LinkedHashSet<>();
        for (StockFillRequest.Fill fill : request.fills()) {
            StockMovement lot = lots.get(fill.lotId());
            for (StockFillRequest.Line line : fill.lines()) {
                StockMovement row = stillEmpty.get(line.movementId());
                int empty = -row.getQuantity();
                int qty = line.quantity();
                stockMovementRepository.save(row.toBuilder()
                        .quantity(-qty)
                        .seller(lot.getSeller())
                        .sourceMovement(lot)
                        .filledAt(now)
                        .filledBy(user)
                        .build());
                if (qty < empty) {
                    stillEmpty.put(line.movementId(), stockMovementRepository.save(StockMovement.builder()
                            .product(row.getProduct())
                            .seller(row.getSeller())
                            .movementType(StockMovementType.STOCK_OUT)
                            .quantity(qty - empty)
                            .location(row.getLocation())
                            .orderLine(row.getOrderLine())
                            .movedOn(row.getMovedOn())
                            .createdBy(row.getCreatedBy())
                            .build()));
                }
                productIds.add(row.getProduct().getId());
                lineIds.add(row.getOrderLine().getId());
                if (LotLedgerServiceImpl.isUndecided(lot)) undecidedLots.add(lot.getId());
            }
        }
        stockMovementRepository.flush();
        // D6 · D5 · D13: the undecided lots split again, the debts follow the new sources — no notice.
        undecidedLots.forEach(id -> lotLedgerService.resplit(stockMovementRepository.findById(id).orElseThrow()));
        productIds.forEach(lotLedgerService::rebuildDebts);
        lineIds.forEach(this::recost);                                                  // D10
    }

    @Override
    @Transactional
    public void recostFilled(Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return;
        }
        Set<Long> lineIds = new LinkedHashSet<>();
        for (StockMovement row : stockMovementRepository.findByFilledAtIsNotNullAndSourceMovementIdIn(lotIds)) {
            lineIds.add(row.getOrderLine().getId());
        }
        lineIds.forEach(this::recost);
    }

    /**
     * D10 · D20 · D25 · D26: one order line costed again — the filled rows' pieces at their lot's unit price (an undecided
     * lot: each share's unit price, the undecided part at the registered price), the other rows taken from an undecided
     * lot at the registered price, the rest by the purchase rule. A line that no longer expands keeps its cost.
     */
    private void recost(Long orderLineId) {
        OrderLine line = orderLineRepository.findWithListingOptionById(orderLineId).orElse(null);
        if (line == null) return;
        OrderLineExpander.LineExpansion expansion =
                orderLineExpander.expand(List.of(line), OrderLine::getOrderQty).get(orderLineId);
        if (expansion == null || expansion.failed()) return;
        List<StockMovement> outs = stockMovementRepository
                .findByOrderLineIdAndMovementTypeOrderByIdAsc(orderLineId, StockMovementType.STOCK_OUT);
        List<Long> undecidedOuts = outs.stream()
                .filter(m -> m.getSourceMovement() != null && LotLedgerServiceImpl.isUndecided(m.getSourceMovement()))
                .map(StockMovement::getId).toList();
        Map<Long, List<LotShareUsage>> usages = undecidedOuts.isEmpty() ? Map.of()
                : lotShareUsageRepository.findByOutMovementIdIn(undecidedOuts).stream()
                .collect(Collectors.groupingBy(u -> u.getOutMovement().getId()));
        Map<Long, List<CostBasisResolver.FilledPiece>> filled = new LinkedHashMap<>();
        Map<Long, Integer> undecidedQty = new LinkedHashMap<>();
        for (StockMovement out : outs) {
            StockMovement lot = out.getSourceMovement();
            if (lot == null) continue;
            Long productId = out.getProduct().getId();
            boolean undecided = LotLedgerServiceImpl.isUndecided(lot);
            if (!out.isFilledLater()) {
                if (undecided) undecidedQty.merge(productId, -out.getQuantity(), Integer::sum);
                continue;
            }
            List<CostBasisResolver.FilledPiece> pieces = filled.computeIfAbsent(productId, k -> new ArrayList<>());
            if (!undecided) {
                pieces.add(new CostBasisResolver.FilledPiece(lot.getUnitPrice(), false, -out.getQuantity()));
                continue;
            }
            for (LotShareUsage usage : usages.getOrDefault(out.getId(), List.of())) {
                PurchaseRecord share = usage.getShareRecord();
                pieces.add(share == null
                        ? new CostBasisResolver.FilledPiece(null, true, usage.getQuantity())
                        : new CostBasisResolver.FilledPiece(share.getUnitPrice(), false, usage.getQuantity()));
            }
        }
        if (filled.isEmpty()) return;
        costBasisResolver.refill(line, expansion.products(), filled, undecidedQty);
    }

    /** D17: the lot owner's rows first, then the others — each part keeps day then record order. */
    private static List<StockMovement> fillOrder(List<StockMovement> rows, StockMovement lot) {
        List<StockMovement> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(m -> sameSeller(orderSeller(m), lot.getSeller()) ? 0 : 1));
        return ordered;
    }

    private LocalDateTime linkedAtOrThrow() {
        if (!purchaseSettingService.isStockLinked()) {
            throw new IllegalArgumentException("재고 연동이 꺼져 있습니다.");
        }
        return purchaseSettingService.stockLinkedAt();
    }

    private StockMovement fillableRow(Long movementId, LocalDateTime linkedAt) {
        StockMovement row = stockMovementRepository.findById(movementId)
                .orElseThrow(() -> new ResourceNotFoundException("StockMovement", movementId));
        if (!row.isFillableOutbound(linkedAt)) {
            throw new IllegalArgumentException("채울 수 없는 줄입니다.");
        }
        return row;
    }

    private static String sellerName(StockMovement lot) {
        return lot.getSeller() == null ? null : lot.getSeller().getSellerName();
    }

    private static Seller orderSeller(StockMovement out) {
        return out.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
    }

    private static boolean sameSeller(Seller a, Seller b) {
        return a != null && b != null && Objects.equals(a.getId(), b.getId());
    }

    /** Same source as StockLedgerServiceImpl.currentUsername() (PLAN 2609_28 D9). */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
