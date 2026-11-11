package com.pms.service.stock;

import com.pms.domain.LotShareUsage;
import com.pms.domain.OrderClaim;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import com.pms.dto.response.StockMovementView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.LotShareUsageRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.PurchaseSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link StockLedgerService} implementation.
 *
 * <p>All per-type rules live in {@link #validate} — one place, so a second screen (mobile, bulk
 * import) cannot invent its own variant of "is a reason required here".
 *
 * <p>Class default is readOnly; {@link #record} and {@link #recordUndecidedIntake} override with a
 * write transaction.
 * Entities have no setters — nothing is mutated, rows are only appended.
 *
 * <p>FEATURE_2610_19: a disposal, an adjust − and a matched return may be written as several rows, one per lot
 * ({@link LotLedgerService}, D50 · D139 ⑤); {@link #record} returns the first of them.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockLedgerServiceImpl implements StockLedgerService {

    /** Default history window when the caller gives no dates. */
    private static final int DEFAULT_HISTORY_DAYS = 30;

    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final SellerRepository sellerRepository;
    private final PurchaseSettingService purchaseSettingService;
    private final LotLedgerService lotLedgerService;
    private final LotShareUsageRepository lotShareUsageRepository;

    @Override
    @Transactional
    public StockMovementView record(StockMovementRequest request) {
        StockMovementType type = request.movementType();
        validate(request, type);

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
        PurchaseRecord purchaseRecord = resolvePurchaseRecord(request, type);
        OrderClaim orderClaim = resolveOrderClaim(request, type);
        Seller seller = resolveSeller(request, type, orderClaim, purchaseRecord);
        int signed = signedQuantity(type, request.quantity());

        List<LotSlice> slices;
        if (type == StockMovementType.DISPOSAL || (type == StockMovementType.ADJUST && signed < 0)) {
            // FEATURE_2611_22 D34 · D35 · D36: with the stock link on, never below the seller's on-hand balance or what
            // is left of the seller's decided lots, whichever is smaller.
            if (purchaseSettingService.isStockLinked()) {
                long onHand = balances(product.getId(), seller.getId(), null).stream()
                        .mapToLong(StockBalanceView::onHand).sum();
                long limit = Math.min(onHand, lotLedgerService.ownLeft(product, seller));
                if (Math.abs(signed) > limit) {
                    throw new IllegalArgumentException("남은 재고 수량(" + limit + "개)보다 많습니다.");
                }
            }
            // FEATURE_2610_19 D50: the row seller's own lots, oldest first.
            slices = lotLedgerService.allocateOwn(product, seller, Math.abs(signed));
        } else if (type == StockMovementType.RETURN_IN && orderClaim.getOrderLine() != null) {
            // FEATURE_2610_19 D139 · D142: back into the order line's own lots, the rest becomes new lots.
            slices = lotLedgerService.allocateReturn(orderClaim.getOrderLine(), product, seller, signed);
        } else {
            slices = List.of(new LotSlice(null, seller, Math.abs(signed),
                    resolveUnitPrice(request, type, product, purchaseRecord, signed)));
        }

        // FEATURE_2611_17 D25: an 「기타」 intake without an amount carries the registered price, marked 「미기입」.
        LocalDateTime priceFromBaseAt = isEtcFilledFromBase(request, type, product) ? LocalDateTime.now() : null;
        StockMovement first = null;
        for (LotSlice slice : slices) {
            StockMovement saved = stockMovementRepository.save(StockMovement.builder()
                    .product(product)
                    .seller(slice.seller())
                    .movementType(type)
                    .quantity(signed < 0 ? -slice.quantity() : slice.quantity())
                    .location(StockLocationPolicy.resolve(product))
                    .reason(request.reason())
                    .reasonNote(request.reasonNote())
                    .unitPrice(slice.unitPrice())
                    .priceFromBaseAt(priceFromBaseAt)
                    .sourceMovement(slice.source())
                    .orderClaim(orderClaim)
                    .purchaseRecord(purchaseRecord)
                    .movedOn(request.movedOn())
                    .createdBy(currentUsername())
                    .build());
            if (first == null) {
                first = saved;
            }
        }
        return toView(first);
    }

    /**
     * An undecided intake row (FEATURE_2610_19 D105 · D117 ⑥): the stock row of an intake-work purchase whose seller
     * is not decided yet. Same row as a purchase check-in, without a seller; the unit price comes from the record.
     */
    @Override
    @Transactional
    public StockMovementView recordUndecidedIntake(PurchaseRecord record) {
        StockMovement saved = stockMovementRepository.save(StockMovement.builder()
                .product(record.getProduct())
                .seller(null)
                .movementType(StockMovementType.STOCK_IN)
                .quantity(record.getQuantity())
                .location(StockLocationPolicy.resolve(record.getProduct()))
                .reason(StockReason.PURCHASE)
                .unitPrice(record.getUnitPrice())
                .purchaseRecord(record)
                .movedOn(record.getPurchasedOn())
                .createdBy(currentUsername())
                .build());
        return toView(saved);
    }

    @Override
    public List<StockBalanceView> balances(Long productId, Long sellerId, String keyword) {
        // Blank -> null: "%%" would look like a broken filter and is indistinguishable from
        // "user typed only spaces", which then gets reported as a bug.
        // The (product × seller) grouping itself lives in the JPQL (2609_29 D5).
        // FEATURE_2610_19 D131: an assigned share of an undecided row belongs to its owner — move what is left of each
        // share from the 「판매자 미정」 row (seller null) to the owner's row, then apply the seller filter.
        // FEATURE_2611_02 D1 · D10: before that filter, drop a 「판매자 미정」 row at 0 whose product has no undecided
        // purchase left (everything went to sellers). D33: the purchase-price amount comes last, for the rows shown.
        List<StockBalanceView> rows = stockMovementRepository.findBalances(productId, null, blankToNull(keyword));
        Map<String, StockBalanceView> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.productId() + ":" + r.sellerId(), r));
        Set<Long> shownProducts = rows.stream().map(StockBalanceView::productId).collect(Collectors.toSet());
        Map<Long, Integer> usedByShare = new HashMap<>();
        List<PurchaseRecord> shares = purchaseRecordRepository.findAllShares().stream()
                .filter(s -> shownProducts.contains(s.getProduct().getId())).toList();
        if (!shares.isEmpty()) {
            for (LotShareUsage usage : lotShareUsageRepository.findByInMovementIdIn(
                    shares.stream().map(s -> s.getSourceMovement().getId()).distinct().toList())) {
                if (usage.getShareRecord() != null) {
                    usedByShare.merge(usage.getShareRecord().getId(), usage.getQuantity(), Integer::sum);
                }
            }
        }
        for (PurchaseRecord share : shares) {
            long left = share.getQuantity() - usedByShare.getOrDefault(share.getId(), 0);
            Long product = share.getProduct().getId();
            StockBalanceView undecided = byKey.get(product + ":null");
            String productName = undecided != null ? undecided.productName() : share.getProduct().getProductName();
            String imageUrl = undecided != null ? undecided.imageUrl() : share.getProduct().getImageUrl();
            byKey.put(product + ":null", new StockBalanceView(product, productName, imageUrl, null, null,
                    (undecided == null ? 0 : undecided.onHand()) - left));
            StockBalanceView owner = byKey.get(product + ":" + share.getSeller().getId());
            byKey.put(product + ":" + share.getSeller().getId(), new StockBalanceView(product, productName, imageUrl,
                    share.getSeller().getId(), share.getSeller().getSellerName(),
                    (owner == null ? 0 : owner.onHand()) + left));
        }
        // D5: the undecided purchase quantity left per product — a product missing from the result has 0.
        Map<Long, Long> undecidedLeft = new HashMap<>();
        if (!shownProducts.isEmpty()) {
            for (Object[] sum : purchaseRecordRepository.sumUndecidedByProduct(shownProducts, null)) {
                undecidedLeft.put((Long) sum[0], ((Number) sum[1]).longValue());
            }
        }
        List<StockBalanceView> shown = byKey.values().stream()
                .filter(r -> !(r.sellerId() == null && r.onHand() == 0
                        && undecidedLeft.getOrDefault(r.productId(), 0L) == 0L))
                .filter(r -> sellerId == null || sellerId.equals(r.sellerId()))
                .toList();
        Map<StockBalanceView, BigDecimal> amounts = lotLedgerService.stockAmounts(shown);
        return shown.stream()
                .map(r -> r.withAmount(amounts.get(r)))
                .sorted(Comparator.comparing(StockBalanceView::productName)
                        .thenComparing(StockBalanceView::sellerName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<StockMovementView> history(Long productId, Long sellerId, LocalDate from, LocalDate to) {
        LocalDate end = (to != null) ? to : LocalDate.now();
        LocalDate start = (from != null) ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        return stockMovementRepository.findHistory(productId, sellerId, start, end).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<PurchaseCandidateView> purchaseCandidates(Long productId) {
        return stockMovementRepository.findPurchaseCandidates(productId);
    }

    @Override
    public List<ReturnCandidateView> returnCandidates() {
        return stockMovementRepository.findReturnCandidates();
    }

    /**
     * Every combination rule for the ledger (prompt 04 Step 4).
     *
     * <table>
     *   <tr><th>type</th><th>quantity</th><th>reason</th><th>required ref</th><th>forbidden ref</th></tr>
     *   <tr><td>STOCK_IN</td><td>&gt; 0</td><td>required</td><td>PURCHASE -&gt; purchaseRecordId (while the stock link is on)</td><td>claim</td></tr>
     *   <tr><td>RETURN_IN</td><td>&gt; 0</td><td>none</td><td>orderClaimId</td><td>purchaseRecord</td></tr>
     *   <tr><td>DISPOSAL</td><td>&gt; 0 (stored negative)</td><td>required</td><td>-</td><td>all</td></tr>
     *   <tr><td>ADJUST</td><td>!= 0</td><td>required</td><td>-</td><td>all</td></tr>
     *   <tr><td>STOCK_OUT</td><td colspan="4">rejected here — outbound starts from an order</td></tr>
     * </table>
     *
     * @throws IllegalArgumentException any rule violation (-> 400 via GlobalExceptionHandler)
     */
    private void validate(StockMovementRequest request, StockMovementType type) {
        if (type == StockMovementType.STOCK_OUT) {
            throw new IllegalArgumentException("출고는 이 화면에서 기록할 수 없습니다");
        }

        int quantity = request.quantity();
        if (type == StockMovementType.ADJUST) {
            if (quantity == 0) {
                throw new IllegalArgumentException("조정 수량은 0일 수 없습니다");
            }
        } else if (quantity <= 0) {
            // Disposal is entered as "3 discarded"; the server flips the sign (never the client).
            throw new IllegalArgumentException("수량은 0보다 커야 합니다");
        }

        StockReason reason = request.reason();
        boolean reasonRequired = type == StockMovementType.STOCK_IN
                || type == StockMovementType.DISPOSAL
                || type == StockMovementType.ADJUST;
        if (reasonRequired && reason == null) {
            throw new IllegalArgumentException("사유를 선택하세요");
        }
        if (reason != null && !reason.allowedFor(type)) {
            // Covers RETURN_IN too: no reason belongs to it, so any value is rejected.
            throw new IllegalArgumentException("선택한 사유는 이 이동 유형에 사용할 수 없습니다");
        }
        if (reason == StockReason.ETC && isBlank(request.reasonNote())) {
            throw new IllegalArgumentException("기타 사유는 설명을 입력해야 합니다");
        }

        if (type == StockMovementType.RETURN_IN) {
            if (request.orderClaimId() == null) {
                throw new IllegalArgumentException("반품 건을 선택하세요");
            }
            if (request.purchaseRecordId() != null) {
                throw new IllegalArgumentException("반품 입고는 구매기록을 참조할 수 없습니다");
            }
            return;
        }

        // STOCK_IN / DISPOSAL / ADJUST never belong to a single order (D6): one delivery is consumed
        // by many orders, so tying an inbound row to one of them is structurally wrong.
        if (request.orderClaimId() != null) {
            throw new IllegalArgumentException("이 이동 유형은 주문·클레임을 참조할 수 없습니다");
        }
        if (type == StockMovementType.STOCK_IN) {
            // FEATURE_2610_09 D71 (round 12): with the stock link off, a PURCHASE check-in here is the
            // stock screen's own entry and needs no purchase record. With it on, the record stays mandatory.
            if (reason == StockReason.PURCHASE && request.purchaseRecordId() == null
                    && purchaseSettingService.isStockLinked()) {
                throw new IllegalArgumentException("구매기록을 선택하세요");
            }
        } else if (request.purchaseRecordId() != null) {
            throw new IllegalArgumentException("이 이동 유형은 구매기록을 참조할 수 없습니다");
        }
    }

    /** STOCK_IN/RETURN_IN add, DISPOSAL subtracts, ADJUST keeps the sign the user typed. */
    private int signedQuantity(StockMovementType type, int quantity) {
        return type.sign() == 0 ? quantity : Math.abs(quantity) * type.sign();
    }

    /**
     * Unit price per reason (D8).
     *
     * <p>⚠️ {@code PURCHASE} with a purchase record copies the amount from the purchase ledger and
     * <b>ignores the request value</b> — the money-side row is the source of truth. If that amount is
     * unknown the movement is still stored with a null price; substituting 0 would silently claim the
     * goods were free. Without a record (stock link off — {@code validate} rejects it while on,
     * FEATURE_2610_09 D71 round 13) it follows the {@code OPENING} rule below: the typed price, else the
     * registered price.
     *
     * <p>⚠️ {@code OPENING} falls back to the product's registered price when the request carries no
     * unit price — the snapshot the reason was designed around. It is a fallback, not a forced
     * value: a typed price still wins, because the goods on the shelf may have been bought at a
     * different price than the one the product carries today. A product with no price stays null
     * ("amount unknown"), never 0.
     *
     * <p>⚠️ {@code ETC} without an amount takes the registered price too, and the row is marked 「미기입」
     * (FEATURE_2611_17 D25); a product with no price stays null until its price appears (D22).
     */
    private BigDecimal resolveUnitPrice(StockMovementRequest request, StockMovementType type,
                                        Product product, PurchaseRecord purchaseRecord, int signed) {
        if (type == StockMovementType.ADJUST && signed > 0 && purchaseSettingService.isStockLinked()) {
            // FEATURE_2610_19 D50: an adjust + is a new lot at the registered price.
            return product.getPrice();
        }
        if (type == StockMovementType.RETURN_IN) {
            // FEATURE_2610_19 D140: an order-unmatched return is a new lot at the registered price (stock link on).
            return purchaseSettingService.isStockLinked() ? product.getPrice() : null;
        }
        if (type != StockMovementType.STOCK_IN) {
            return null;
        }
        return switch (request.reason()) {
            case PURCHASE -> purchaseRecord != null
                    ? purchaseRecord.getUnitPrice()
                    : (request.unitPrice() != null ? request.unitPrice() : product.getPrice());
            case FREE -> BigDecimal.ZERO;
            case OPENING -> request.unitPrice() != null ? request.unitPrice() : product.getPrice();
            // ETC (optional): FEATURE_2611_17 D25 — no amount → the registered price (null when the product has none).
            default -> isEtcFilledFromBase(request, type, product) ? product.getPrice() : request.unitPrice();
        };
    }

    /** FEATURE_2611_17 D25: an 「기타」 stock-in saved without an amount while the product has a registered price. */
    private static boolean isEtcFilledFromBase(StockMovementRequest request, StockMovementType type, Product product) {
        return type == StockMovementType.STOCK_IN && request.reason() == StockReason.ETC
                && request.unitPrice() == null && product.getPrice() != null;
    }

    /**
     * Whose stock moved (PLAN 2609_29 D4·D22).
     *
     * <table>
     *   <tr><th>type</th><th>seller</th></tr>
     *   <tr><td>RETURN_IN</td><td>derived from the claim — the screen never asks</td></tr>
     *   <tr><td>everything else</td><td>the request's {@code sellerId}, mandatory</td></tr>
     * </table>
     *
     * <p>🔴 A RETURN_IN claim can be order-unmatched ({@code orderLine == null}); then the seller is the claim's sales
     * channel's seller (FEATURE_2610_19 D140) — the request's seller is never used for a return.
     *
     * <p>🔴 For a PURCHASE check-in with a purchase record the request seller must equal the record's. If
     * the two ledgers are allowed to disagree, nobody can decide afterwards which one was right.
     *
     * @throws IllegalArgumentException seller missing or contradicting the purchase record (-> 400)
     */
    private Seller resolveSeller(StockMovementRequest request, StockMovementType type,
                                 OrderClaim orderClaim, PurchaseRecord purchaseRecord) {
        if (type == StockMovementType.RETURN_IN) {
            return sellerOfClaim(orderClaim);
        }
        if (request.sellerId() == null) {
            throw new IllegalArgumentException("판매자를 선택하세요");
        }
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));
        if (purchaseRecord != null && (purchaseRecord.getSeller() == null
                || !purchaseRecord.getSeller().getId().equals(seller.getId()))) {
            throw new IllegalArgumentException("구매기록의 판매자와 입고 판매자가 다릅니다");
        }
        return seller;
    }

    /**
     * claim -> orderLine -> order -> marketplaceAccount -> seller; an order-unmatched claim ->
     * its own marketplaceAccount -> seller (FEATURE_2610_19 D140 — the account and its seller are NOT NULL).
     */
    private Seller sellerOfClaim(OrderClaim orderClaim) {
        if (orderClaim.getOrderLine() == null) {
            return orderClaim.getMarketplaceAccount().getSeller();
        }
        return orderClaim.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
    }

    private PurchaseRecord resolvePurchaseRecord(StockMovementRequest request, StockMovementType type) {
        if (type != StockMovementType.STOCK_IN || request.reason() != StockReason.PURCHASE
                || request.purchaseRecordId() == null) {
            return null;
        }
        return purchaseRecordRepository.findById(request.purchaseRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseRecord", request.purchaseRecordId()));
    }

    private OrderClaim resolveOrderClaim(StockMovementRequest request, StockMovementType type) {
        if (type != StockMovementType.RETURN_IN) {
            return null;
        }
        return orderClaimRepository.findById(request.orderClaimId())
                .orElseThrow(() -> new ResourceNotFoundException("OrderClaim", request.orderClaimId()));
    }

    private StockMovementView toView(StockMovement m) {
        return new StockMovementView(
                m.getId(),
                m.getProduct().getId(),
                m.getProduct().getProductName(),
                m.getProduct().getImageUrl(),
                m.getSeller() == null ? null : m.getSeller().getId(),
                m.getSeller() == null ? null : m.getSeller().getSellerName(),
                m.getMovementType(),
                m.getQuantity(),
                m.getReason(),
                m.getReasonNote(),
                m.getUnitPrice(),
                m.getOrderLine() == null ? null : m.getOrderLine().getId(),
                m.getOrderClaim() == null ? null : m.getOrderClaim().getId(),
                m.getPurchaseRecord() == null ? null : m.getPurchaseRecord().getId(),
                m.getMovedOn(),
                m.getCreatedBy(),
                List.of());
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Same source as OrderCancelAdapter.currentUsername() (PLAN 2609_28 D9) — do not introduce a second way. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
