package com.pms.service.stock;

import com.pms.domain.LotShareUsage;
import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerDebt;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.response.SellerSalesResponse;
import com.pms.repository.LotShareUsageRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.PurchaseSettingService;
import com.pms.service.sales.SalesStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

/**
 * {@link LotLedgerService} implementation (FEATURE_2610_19).
 *
 * <p>Every calculation reads one product's whole ledger in record order ({@code id}, D107) into a {@link Ledger} and
 * works in memory — a lot's remaining quantity is derived, never stored (2609_28 D14):
 * <pre>
 *   lot remaining   = lot quantity + Σ quantity of the rows whose source is that lot   (outgoing −, returns +)
 *   share remaining = assigned quantity − Σ split pieces of that share                  (D130 · D131)
 *   undecided part  = lot quantity − Σ assigned quantity − Σ undecided split pieces
 * </pre>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LotLedgerServiceImpl implements LotLedgerService {

    private final StockMovementRepository stockMovementRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final LotShareUsageRepository lotShareUsageRepository;
    private final SellerDebtRepository sellerDebtRepository;
    private final PurchaseSettingService purchaseSettingService;
    private final SalesStatsService salesStatsService;

    // ------------------------------------------------------------------ allocation

    @Override
    public List<LotSlice> allocateOutbound(Product product, Seller orderSeller, int quantity, LocalDate movedOn) {
        if (!purchaseSettingService.isStockLinked()) {
            return List.of(new LotSlice(null, orderSeller, quantity, null));
        }
        Ledger ledger = load(product.getId());
        List<Piece> own = new ArrayList<>();
        List<Piece> others = new ArrayList<>();
        for (Piece piece : ledger.pieces(null)) {
            if (piece.available <= 0) continue;
            if (sameSeller(piece.owner, orderSeller)) own.add(piece); else others.add(piece);
        }
        // D32 · D108: own goods oldest first.
        own.sort(Comparator.comparing((Piece p) -> p.lot.getMovedOn())
                .thenComparing(p -> p.lot.getId()).thenComparing(Piece::shareId));
        if (!others.isEmpty()) {
            Map<Long, BigDecimal> rank = salesRank(movedOn);
            others.sort(othersOrder(ledger, null, rank));
        }
        List<Piece> order = new ArrayList<>(own);
        order.addAll(others);
        return take(order, quantity, orderSeller);
    }

    @Override
    public List<LotSlice> allocateOwn(Product product, Seller seller, int quantity) {
        if (!purchaseSettingService.isStockLinked()) {
            return List.of(new LotSlice(null, seller, quantity, null));
        }
        Ledger ledger = load(product.getId());
        List<Piece> own = new ArrayList<>();
        for (Piece piece : ledger.pieces(null)) {
            // D116 ③ · D131: a disposal never uses an undecided lot, not even an owner's share of one.
            if (piece.available > 0 && !isUndecided(piece.lot) && sameSeller(piece.owner, seller)) own.add(piece);
        }
        own.sort(Comparator.comparing((Piece p) -> p.lot.getMovedOn()).thenComparing(p -> p.lot.getId()));
        return take(own, quantity, seller);
    }

    @Override
    public List<LotSlice> allocateReturn(OrderLine line, Product product, Seller orderSeller, int quantity) {
        if (!purchaseSettingService.isStockLinked()) {
            return List.of(new LotSlice(null, orderSeller, quantity, null));
        }
        Ledger ledger = load(product.getId());
        List<StockMovement> outs = ledger.rows.stream()
                .filter(m -> m.getMovementType() == StockMovementType.STOCK_OUT && m.getOrderLine() != null
                        && m.getOrderLine().getId().equals(line.getId()))
                .toList();
        Map<String, BigDecimal> debtUnit = new HashMap<>();
        if (!outs.isEmpty()) {
            for (SellerDebt debt : sellerDebtRepository.findByOutMovementIdIn(outs.stream().map(StockMovement::getId).toList())) {
                debtUnit.put(debtKey(debt.getOutMovement().getId(), debt.getShareRecord()), debt.getUnitPrice());
            }
        }
        List<ReturnPiece> pieces = new ArrayList<>();
        for (StockMovement out : outs) {
            int shipped = -out.getQuantity();
            StockMovement lot = out.getSourceMovement() == null ? null : ledger.byId.get(out.getSourceMovement().getId());
            if (lot == null) {
                // D139 ④: no lot was used — a new lot at the registered price.
                pieces.add(new ReturnPiece(null, out, null, product.getPrice(), shipped, 0));
            } else if (!isUndecided(lot)) {
                if (sameSeller(lot.getSeller(), orderSeller)) {
                    // D139 ②: back into the order seller's own lot.
                    pieces.add(new ReturnPiece(lot, out, lot, null, shipped, 0));
                } else {
                    // D139 ③ · D106: borrowed goods become a new lot of the order seller at the debt's unit price.
                    pieces.add(new ReturnPiece(lot, out, null, debtUnit.get(debtKey(out.getId(), null)), shipped, 0));
                }
            } else {
                // D142 ⑤ ⑥: an undecided lot — every share becomes a new lot of the order seller.
                List<LotShareUsage> usages = new ArrayList<>(ledger.usagesOfOut(out.getId()));
                usages.sort(Comparator.comparing((LotShareUsage u) -> returnUnit(u, out, orderSeller, product, debtUnit),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(u -> shareOrder(u, orderSeller)));
                int index = 0;
                for (LotShareUsage usage : usages) {
                    pieces.add(new ReturnPiece(lot, out, null, returnUnit(usage, out, orderSeller, product, debtUnit),
                            usage.getQuantity(), index++));
                }
            }
        }
        // D139 ① ⑦: dearest lot first (an empty price counts as the cheapest), then D108 order; a piece without a lot
        // comes after every lot piece, in record order.
        pieces.sort(Comparator.comparing((ReturnPiece p) -> p.lot == null ? null : p.lot.getUnitPrice(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(p -> p.lot == null ? LocalDate.MAX : p.lot.getMovedOn())
                .thenComparing(p -> p.lot == null ? Long.MAX_VALUE : p.lot.getId())
                .thenComparing(p -> p.out.getId())
                .thenComparingInt(p -> p.index));

        // D139 ①: the earlier returns of this order line already took the first pieces.
        int skip = ledger.rows.stream()
                .filter(m -> m.getMovementType() == StockMovementType.RETURN_IN && m.getOrderClaim() != null
                        && m.getOrderClaim().getOrderLine() != null
                        && m.getOrderClaim().getOrderLine().getId().equals(line.getId()))
                .mapToInt(StockMovement::getQuantity).sum();
        List<LotSlice> slices = new ArrayList<>();
        int left = quantity;
        for (ReturnPiece piece : pieces) {
            int usable = piece.quantity;
            if (skip > 0) {
                int skipped = Math.min(skip, usable);
                skip -= skipped;
                usable -= skipped;
            }
            if (usable <= 0 || left == 0) continue;
            int qty = Math.min(usable, left);
            slices.add(new LotSlice(piece.target, orderSeller, qty, piece.target == null ? piece.unitPrice : null));
            left -= qty;
        }
        if (left > 0) {
            slices.add(new LotSlice(null, orderSeller, left, product.getPrice()));
        }
        return slices;
    }

    // ------------------------------------------------------------------ after writes

    @Override
    @Transactional
    public Map<Long, List<String>> afterOutbound(Long productId, List<StockMovement> savedRows) {
        Set<Long> undecidedLots = new LinkedHashSet<>();
        for (StockMovement row : savedRows) {
            if (row.getSourceMovement() != null && row.getSeller() == null) {
                undecidedLots.add(row.getSourceMovement().getId());
            }
        }
        for (Long lotId : undecidedLots) {
            resplit(stockMovementRepository.findById(lotId).orElseThrow());
        }
        rebuildDebts(productId);
        Map<Long, List<String>> notices = new LinkedHashMap<>();
        List<Long> ids = savedRows.stream().map(StockMovement::getId).toList();
        for (SellerDebt debt : sellerDebtRepository.findByOutMovementIdInOrderByIdAsc(ids)) {
            notices.computeIfAbsent(debt.getOutMovement().getId(), k -> new ArrayList<>()).add(notice(debt));
        }
        return notices;
    }

    @Override
    @Transactional
    public void resplit(StockMovement undecidedLot) {
        Long lotId = undecidedLot.getId();
        lotShareUsageRepository.deleteByInMovementId(lotId);
        Ledger ledger = load(undecidedLot.getProduct().getId());
        StockMovement lot = ledger.byId.get(lotId);
        List<PurchaseRecord> shares = ledger.sharesByLot.getOrDefault(lotId, List.of());
        Map<Long, Integer> left = new HashMap<>();
        for (PurchaseRecord share : shares) left.put(share.getId(), share.getQuantity());
        int undecidedLeft = lot.getQuantity() - shares.stream().mapToInt(PurchaseRecord::getQuantity).sum();

        List<LotShareUsage> written = new ArrayList<>();
        for (StockMovement out : ledger.rows) {
            if (out.getMovementType() != StockMovementType.STOCK_OUT || out.getSourceMovement() == null
                    || !out.getSourceMovement().getId().equals(lotId)) continue;
            Seller orderSeller = orderSeller(out);
            int need = -out.getQuantity();
            // D131: the order seller's shares first, then the other owners' shares and the undecided part together,
            // dearest first (the undecided part at the lot's unit price), a decided share before the undecided part
            // at the same price (D116 ①), then seller rank.
            List<PurchaseRecord> mine = shares.stream().filter(s -> sameSeller(s.getSeller(), orderSeller)).toList();
            List<PurchaseRecord> theirs = new ArrayList<>(shares.stream()
                    .filter(s -> !sameSeller(s.getSeller(), orderSeller)).toList());
            Map<Long, BigDecimal> rank = theirs.isEmpty() ? Map.of() : salesRank(out.getMovedOn());
            theirs.add(null);   // null = the undecided part
            theirs.sort(Comparator.comparing((PurchaseRecord s) -> s == null ? lot.getUnitPrice()
                                    : s.getUnitPrice() != null ? s.getUnitPrice()
                                    : maxPricedBefore(ledger, s.getSeller(), out.getId()),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(s -> s == null ? 1 : 0)
                    .thenComparing(s -> rankOf(rank, s == null ? null : s.getSeller()))
                    .thenComparing(s -> s == null ? Long.MAX_VALUE : s.getId()));
            List<PurchaseRecord> ordered = new ArrayList<>(mine);
            ordered.addAll(theirs);
            int undecidedTaken = 0;
            for (PurchaseRecord share : ordered) {
                if (need == 0) break;
                if (share == null) {
                    undecidedTaken = Math.min(need, Math.max(undecidedLeft, 0));
                    need -= undecidedTaken;
                    continue;
                }
                int qty = Math.min(need, left.get(share.getId()));
                if (qty <= 0) continue;
                left.merge(share.getId(), -qty, Integer::sum);
                need -= qty;
                written.add(ledger.addUsage(lot, out, share, qty));
            }
            // The undecided part also takes what is left when every share ran out (never leave pieces uncounted).
            undecidedTaken += need;
            if (undecidedTaken > 0) {
                undecidedLeft -= undecidedTaken;
                written.add(ledger.addUsage(lot, out, null, undecidedTaken));
            }
        }
        lotShareUsageRepository.saveAll(written);
    }

    @Override
    @Transactional
    public void rebuildDebts(Long productId) {
        sellerDebtRepository.deleteByProductId(productId);
        Ledger ledger = load(productId);
        List<SellerDebt> debts = new ArrayList<>();
        for (StockMovement out : ledger.rows) {
            if (out.getMovementType() != StockMovementType.STOCK_OUT || out.getSourceMovement() == null
                    || out.getOrderLine() == null) continue;
            Seller borrower = orderSeller(out);
            StockMovement lot = ledger.byId.get(out.getSourceMovement().getId());
            if (!isUndecided(lot)) {
                if (lot.getSeller() != null && !sameSeller(lot.getSeller(), borrower)) {
                    BigDecimal unit = lot.getUnitPrice() != null ? lot.getUnitPrice()
                            : maxPricedBefore(ledger, lot.getSeller(), out.getId());
                    debts.add(debt(out, lot, null, borrower, lot.getSeller(), -out.getQuantity(), unit));
                }
                continue;
            }
            for (LotShareUsage usage : ledger.usagesOfOut(out.getId())) {
                PurchaseRecord share = usage.getShareRecord();
                if (share == null || sameSeller(share.getSeller(), borrower)) continue;
                BigDecimal unit = share.getUnitPrice() != null ? share.getUnitPrice()
                        : maxPricedBefore(ledger, share.getSeller(), out.getId());
                debts.add(debt(out, lot, share, borrower, share.getSeller(), usage.getQuantity(), unit));
            }
        }
        sellerDebtRepository.saveAll(debts);
    }

    @Override
    @Transactional
    public void moveOwner(Collection<StockMovement> lots, Collection<PurchaseRecord> shares, Seller newOwner) {
        Set<Long> productIds = new LinkedHashSet<>();
        if (!lots.isEmpty()) {
            List<StockMovement> users = stockMovementRepository
                    .findBySourceMovementIdIn(lots.stream().map(StockMovement::getId).toList());
            List<StockMovement> moved = new ArrayList<>();
            for (StockMovement row : lots) moved.add(row.toBuilder().seller(newOwner).build());
            for (StockMovement row : users) moved.add(row.toBuilder().seller(newOwner).build());
            stockMovementRepository.saveAll(moved);
            lots.forEach(l -> productIds.add(l.getProduct().getId()));
        }
        Set<Long> undecidedLots = new LinkedHashSet<>();
        for (PurchaseRecord share : shares) {
            undecidedLots.add(share.getSourceMovement().getId());
            productIds.add(share.getProduct().getId());
        }
        stockMovementRepository.flush();
        for (Long lotId : undecidedLots) {
            resplit(stockMovementRepository.findById(lotId).orElseThrow());
        }
        productIds.forEach(this::rebuildDebts);
    }

    @Override
    public Map<Long, BigDecimal> outgoingAmounts(Long productId, Collection<Long> outRowIds) {
        Ledger ledger = load(productId);
        Map<Long, BigDecimal> amounts = new HashMap<>();
        for (Long rowId : outRowIds) {
            StockMovement out = ledger.byId.get(rowId);
            if (out == null || out.getSourceMovement() == null) {
                amounts.put(rowId, null);
                continue;
            }
            StockMovement lot = ledger.byId.get(out.getSourceMovement().getId());
            BigDecimal total = BigDecimal.ZERO;
            boolean unknown = false;
            if (!isUndecided(lot)) {
                BigDecimal unit = lot.getUnitPrice() != null ? lot.getUnitPrice()
                        : (lot.getSeller() == null ? null : maxPricedBefore(ledger, lot.getSeller(), rowId));
                if (unit == null) unknown = true;
                else total = unit.multiply(BigDecimal.valueOf(Math.abs(out.getQuantity())));
            } else {
                for (LotShareUsage usage : ledger.usagesOfOut(rowId)) {
                    PurchaseRecord share = usage.getShareRecord();
                    BigDecimal unit = share == null ? lot.getUnitPrice()
                            : (share.getUnitPrice() != null ? share.getUnitPrice()
                            : maxPricedBefore(ledger, share.getSeller(), rowId));
                    if (unit == null) {
                        unknown = true;
                        break;
                    }
                    total = total.add(unit.multiply(BigDecimal.valueOf(usage.getQuantity())));
                }
            }
            amounts.put(rowId, unknown ? null : total.setScale(2, RoundingMode.HALF_UP));
        }
        return amounts;
    }

    // ------------------------------------------------------------------ helpers

    private List<LotSlice> take(List<Piece> order, int quantity, Seller fallbackSeller) {
        Map<Long, Integer> byLot = new LinkedHashMap<>();
        Map<Long, StockMovement> lots = new HashMap<>();
        int left = quantity;
        for (Piece piece : order) {
            if (left == 0) break;
            int qty = Math.min(left, piece.available);
            if (qty <= 0) continue;
            byLot.merge(piece.lot.getId(), qty, Integer::sum);
            lots.put(piece.lot.getId(), piece.lot);
            left -= qty;
        }
        List<LotSlice> slices = new ArrayList<>();
        // D48 ④: one row per lot. D124: the row seller is the lot owner (null for an undecided lot).
        byLot.forEach((id, qty) -> slices.add(new LotSlice(lots.get(id), lots.get(id).getSeller(), qty, null)));
        if (left > 0) {
            // D48 ③: no lot left anywhere — a row without a lot under the order (or row) seller.
            slices.add(new LotSlice(null, fallbackSeller, left, null));
        }
        return slices;
    }

    /** D34 · D116 ① ⑦ · D136 ③: dearest first, decided before undecided, seller rank, then D108 order. */
    private Comparator<Piece> othersOrder(Ledger ledger, Long cutoff, Map<Long, BigDecimal> rank) {
        return Comparator.comparing((Piece p) -> effectivePrice(ledger, p, cutoff),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(p -> p.owner == null ? 1 : 0)
                .thenComparing(p -> rankOf(rank, p.owner))
                .thenComparing(p -> p.lot.getMovedOn())
                .thenComparing(p -> p.lot.getId())
                .thenComparing(Piece::shareId);
    }

    private BigDecimal effectivePrice(Ledger ledger, Piece piece, Long cutoff) {
        if (piece.unitPrice != null) return piece.unitPrice;
        return piece.owner == null ? null : maxPricedBefore(ledger, piece.owner, cutoff);
    }

    /**
     * D136 ①: the dearest priced lot (or share) of {@code owner} that still had at least one piece left just before
     * the row {@code cutoff} (null = now).
     */
    private BigDecimal maxPricedBefore(Ledger ledger, Seller owner, Long cutoff) {
        BigDecimal max = null;
        for (Piece piece : ledger.pieces(cutoff)) {
            if (piece.available >= 1 && piece.unitPrice != null && sameSeller(piece.owner, owner)
                    && (max == null || piece.unitPrice.compareTo(max) > 0)) {
                max = piece.unitPrice;
            }
        }
        return max;
    }

    /** D44 · D116 ⑦: gross sales of the month before {@code movedOn}, per seller id. */
    private Map<Long, BigDecimal> salesRank(LocalDate movedOn) {
        LocalDate day = movedOn == null ? LocalDate.now() : movedOn;
        LocalDate from = day.withDayOfMonth(1).minusMonths(1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        Map<Long, BigDecimal> rank = new HashMap<>();
        for (SellerSalesResponse row : salesStatsService.summary(from, to, null)) {
            rank.put(row.sellerId(), row.grossSales() == null ? BigDecimal.ZERO : row.grossSales());
        }
        return rank;
    }

    /** Sort key: larger sales first, then the smaller seller id; an undecided owner sorts last. */
    private RankKey rankOf(Map<Long, BigDecimal> rank, Seller seller) {
        if (seller == null) return new RankKey(BigDecimal.valueOf(Long.MIN_VALUE), Long.MAX_VALUE);
        return new RankKey(rank.getOrDefault(seller.getId(), BigDecimal.ZERO), seller.getId());
    }

    private record RankKey(BigDecimal sales, Long sellerId) implements Comparable<RankKey> {
        @Override
        public int compareTo(RankKey other) {
            int bySales = other.sales.compareTo(sales);
            return bySales != 0 ? bySales : sellerId.compareTo(other.sellerId);
        }
    }

    private BigDecimal returnUnit(LotShareUsage usage, StockMovement out, Seller orderSeller, Product product,
                                  Map<String, BigDecimal> debtUnit) {
        PurchaseRecord share = usage.getShareRecord();
        if (share == null) return product.getPrice();                                     // D142 ⑤ (c)
        if (sameSeller(share.getSeller(), orderSeller)) return share.getUnitPrice();       // D142 ⑤ (b)
        return debtUnit.get(debtKey(out.getId(), share));                                  // D142 ⑤ (a)
    }

    /** D142 ⑥ tie order = D131: the order seller's share, then the other owners, then the undecided part. */
    private int shareOrder(LotShareUsage usage, Seller orderSeller) {
        PurchaseRecord share = usage.getShareRecord();
        if (share == null) return 2;
        return sameSeller(share.getSeller(), orderSeller) ? 0 : 1;
    }

    private String debtKey(Long outId, PurchaseRecord share) {
        return outId + ":" + (share == null ? "-" : share.getId());
    }

    private SellerDebt debt(StockMovement out, StockMovement lot, PurchaseRecord share, Seller borrower, Seller lender,
                            int quantity, BigDecimal unit) {
        BigDecimal amount = unit == null ? null
                : unit.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        return SellerDebt.builder()
                .product(out.getProduct()).borrower(borrower).lender(lender)
                .outMovement(out).sourceMovement(lot).shareRecord(share)
                .quantity(quantity).unitPrice(unit).amount(amount).movedOn(out.getMovedOn())
                .build();
    }

    /** D49 · D137: 「○○ 재고 N개 사용(채무 ₩M)」 or 「… (채무 미기입)」. One line per debt row. */
    static String notice(SellerDebt debt) {
        String money = debt.getAmount() == null ? "채무 미기입"
                : "채무 ₩" + String.format("%,d", debt.getAmount().setScale(0, RoundingMode.HALF_UP).longValue());
        return debt.getLender().getSellerName() + " 재고 " + debt.getQuantity() + "개 사용(" + money + ")";
    }

    static boolean isUndecided(StockMovement lot) {
        return lot.getSeller() == null && lot.getMovementType() == StockMovementType.STOCK_IN;
    }

    private static boolean sameSeller(Seller a, Seller b) {
        return a != null && b != null && Objects.equals(a.getId(), b.getId());
    }

    private static Seller orderSeller(StockMovement out) {
        return out.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
    }

    private Ledger load(Long productId) {
        List<StockMovement> rows = stockMovementRepository.findByProductIdOrderByIdAsc(productId);
        List<PurchaseRecord> shares = purchaseRecordRepository.findSharesOfProduct(productId);
        List<Long> undecided = rows.stream().filter(m -> isLot(m) && isUndecided(m)).map(StockMovement::getId).toList();
        List<LotShareUsage> usages = undecided.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(lotShareUsageRepository.findByInMovementIdIn(undecided));
        return new Ledger(rows, shares, usages);
    }

    /** A lot = an incoming row that is not itself a return into another lot (D50 · D139). */
    static boolean isLot(StockMovement m) {
        return m.getQuantity() > 0 && m.getSourceMovement() == null
                && (m.getMovementType() == StockMovementType.STOCK_IN
                || m.getMovementType() == StockMovementType.RETURN_IN
                || m.getMovementType() == StockMovementType.ADJUST);
    }

    /** One takeable part of a lot: the whole lot, or one owner's share / the undecided part of an undecided lot. */
    private record Piece(StockMovement lot, PurchaseRecord share, Seller owner, BigDecimal unitPrice, int available) {
        long shareId() {
            return share == null ? Long.MAX_VALUE : share.getId();
        }
    }

    private record ReturnPiece(StockMovement lot, StockMovement out, StockMovement target, BigDecimal unitPrice,
                               int quantity, int index) {
    }

    /** One product's ledger in record order. */
    private static final class Ledger {
        final List<StockMovement> rows;
        final Map<Long, StockMovement> byId = new HashMap<>();
        final Map<Long, List<PurchaseRecord>> sharesByLot = new HashMap<>();
        final List<LotShareUsage> usages;

        Ledger(List<StockMovement> rows, List<PurchaseRecord> shares, List<LotShareUsage> usages) {
            this.rows = rows;
            rows.forEach(r -> byId.put(r.getId(), r));
            for (PurchaseRecord share : shares) {
                if (share.getQuantity() > 0) {
                    sharesByLot.computeIfAbsent(share.getSourceMovement().getId(), k -> new ArrayList<>()).add(share);
                }
            }
            this.usages = usages;
        }

        LotShareUsage addUsage(StockMovement lot, StockMovement out, PurchaseRecord share, int qty) {
            LotShareUsage usage = LotShareUsage.builder()
                    .inMovement(lot).outMovement(out).shareRecord(share).quantity(qty).build();
            usages.add(usage);
            return usage;
        }

        List<LotShareUsage> usagesOfOut(Long outId) {
            return usages.stream().filter(u -> u.getOutMovement().getId().equals(outId)).toList();
        }

        /** Every piece with what was left just before row {@code cutoff} (null = now). */
        List<Piece> pieces(Long cutoff) {
            Map<Long, Integer> flow = new HashMap<>();
            for (StockMovement row : rows) {
                if (row.getSourceMovement() == null) continue;
                if (cutoff != null && row.getId() >= cutoff) continue;
                flow.merge(row.getSourceMovement().getId(), row.getQuantity(), Integer::sum);
            }
            Map<String, Integer> used = new HashMap<>();
            for (LotShareUsage usage : usages) {
                if (cutoff != null && usage.getOutMovement().getId() >= cutoff) continue;
                String key = usage.getInMovement().getId() + ":"
                        + (usage.getShareRecord() == null ? "-" : usage.getShareRecord().getId());
                used.merge(key, usage.getQuantity(), Integer::sum);
            }
            List<Piece> pieces = new ArrayList<>();
            for (StockMovement lot : rows) {
                if (!isLot(lot) || (cutoff != null && lot.getId() >= cutoff)) continue;
                if (!isUndecided(lot)) {
                    pieces.add(new Piece(lot, null, lot.getSeller(), lot.getUnitPrice(),
                            lot.getQuantity() + flow.getOrDefault(lot.getId(), 0)));
                    continue;
                }
                int assigned = 0;
                for (PurchaseRecord share : sharesByLot.getOrDefault(lot.getId(), List.of())) {
                    assigned += share.getQuantity();
                    pieces.add(new Piece(lot, share, share.getSeller(), share.getUnitPrice(),
                            share.getQuantity() - used.getOrDefault(lot.getId() + ":" + share.getId(), 0)));
                }
                pieces.add(new Piece(lot, null, null, lot.getUnitPrice(),
                        lot.getQuantity() - assigned - used.getOrDefault(lot.getId() + ":-", 0)));
            }
            return pieces;
        }
    }
}
