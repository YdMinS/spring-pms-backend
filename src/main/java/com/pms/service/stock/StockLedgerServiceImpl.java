package com.pms.service.stock;

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
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link StockLedgerService} implementation.
 *
 * <p>All per-type rules live in {@link #validate} — one place, so a second screen (mobile, bulk
 * import) cannot invent its own variant of "is a reason required here".
 *
 * <p>Class default is readOnly; only {@link #record} overrides with a write transaction.
 * Entities have no setters — nothing is mutated, rows are only appended.
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

        StockMovement saved = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .seller(seller)
                .movementType(type)
                .quantity(signedQuantity(type, request.quantity()))
                .location(StockLocationPolicy.resolve(product))
                .reason(request.reason())
                .reasonNote(request.reasonNote())
                .unitPrice(resolveUnitPrice(request, type, purchaseRecord))
                .orderClaim(orderClaim)
                .purchaseRecord(purchaseRecord)
                .movedOn(request.movedOn())
                .createdBy(currentUsername())
                .build());
        return toView(saved);
    }

    @Override
    public List<StockBalanceView> balances(Long productId, Long sellerId, String keyword) {
        // Blank -> null: "%%" would look like a broken filter and is indistinguishable from
        // "user typed only spaces", which then gets reported as a bug.
        // The (product × seller) grouping itself lives in the JPQL (2609_29 D5) — this is a delegation.
        return stockMovementRepository.findBalances(productId, sellerId, blankToNull(keyword));
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
     *   <tr><td>STOCK_IN</td><td>&gt; 0</td><td>required</td><td>PURCHASE -&gt; purchaseRecordId</td><td>claim</td></tr>
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
            if (reason == StockReason.PURCHASE && request.purchaseRecordId() == null) {
                throw new IllegalArgumentException("구매기록을 선택하세요");
            }
            if (reason == StockReason.OPENING && request.unitPrice() == null) {
                throw new IllegalArgumentException("기초재고는 단가를 입력해야 합니다");
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
     * <p>⚠️ {@code PURCHASE} copies the amount from the purchase ledger and <b>ignores the request
     * value</b> — the money-side row is the source of truth. If that amount is unknown the movement
     * is still stored with a null price; substituting 0 would silently claim the goods were free.
     */
    private BigDecimal resolveUnitPrice(StockMovementRequest request, StockMovementType type,
                                        PurchaseRecord purchaseRecord) {
        if (type != StockMovementType.STOCK_IN) {
            return null;
        }
        return switch (request.reason()) {
            case PURCHASE -> purchaseRecord == null ? null : purchaseRecord.getUnitPrice();
            case FREE -> BigDecimal.ZERO;
            default -> request.unitPrice();   // OPENING (required) / ETC (optional)
        };
    }

    /**
     * Whose stock moved (PLAN 2609_29 D4·D22).
     *
     * <table>
     *   <tr><th>type</th><th>seller</th></tr>
     *   <tr><td>RETURN_IN</td><td>derived from the claim's order — the screen never asks</td></tr>
     *   <tr><td>everything else</td><td>the request's {@code sellerId}, mandatory</td></tr>
     * </table>
     *
     * <p>🔴 A RETURN_IN claim can be order-unmatched ({@code orderLine == null}), and then there is
     * nothing to derive from — the request must carry the seller instead.
     *
     * <p>🔴 For a PURCHASE check-in the request seller must equal the purchase record's. If the two
     * ledgers are allowed to disagree, nobody can decide afterwards which one was right.
     *
     * @throws IllegalArgumentException seller missing or contradicting the purchase record (-> 400)
     */
    private Seller resolveSeller(StockMovementRequest request, StockMovementType type,
                                 OrderClaim orderClaim, PurchaseRecord purchaseRecord) {
        if (type == StockMovementType.RETURN_IN) {
            Seller derived = sellerOfClaim(orderClaim);
            if (derived != null) {
                return derived;
            }
        }
        if (request.sellerId() == null) {
            throw new IllegalArgumentException("판매자를 선택하세요");
        }
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));
        if (purchaseRecord != null && !purchaseRecord.getSeller().getId().equals(seller.getId())) {
            throw new IllegalArgumentException("구매기록의 판매자와 입고 판매자가 다릅니다");
        }
        return seller;
    }

    /** claim -> orderLine -> order -> marketplaceAccount -> seller. null when the claim is unmatched. */
    private Seller sellerOfClaim(OrderClaim orderClaim) {
        if (orderClaim == null || orderClaim.getOrderLine() == null) {
            return null;
        }
        return orderClaim.getOrderLine().getOrder().getMarketplaceAccount().getSeller();
    }

    private PurchaseRecord resolvePurchaseRecord(StockMovementRequest request, StockMovementType type) {
        if (type != StockMovementType.STOCK_IN || request.reason() != StockReason.PURCHASE) {
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
                m.getSeller().getId(),
                m.getSeller().getSellerName(),
                m.getMovementType(),
                m.getQuantity(),
                m.getReason(),
                m.getReasonNote(),
                m.getUnitPrice(),
                m.getOrderLine() == null ? null : m.getOrderLine().getId(),
                m.getOrderClaim() == null ? null : m.getOrderClaim().getId(),
                m.getPurchaseRecord() == null ? null : m.getPurchaseRecord().getId(),
                m.getMovedOn(),
                m.getCreatedBy());
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Same source as OrderCancelServiceImpl (PLAN 2609_28 D9) — do not introduce a second way. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
