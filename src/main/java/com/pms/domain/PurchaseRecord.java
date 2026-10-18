package com.pms.domain;

import com.pms.dto.request.PurchaseRecordRequest;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Purchase history (product × seller) — the money-side ledger.
 *
 * <p>🔴 Not tied to an order line (PLAN 2609_29 D3). A check-in means "this seller brought in this many of this
 * product", not "this order's share" — the attribution must survive when there is no order (manual restock) or the
 * order is cancelled, or settlement breaks.
 *
 * <p>Purchased quantity on the purchase list = SUM(quantity) per (product × seller) (FEATURE_2610_09 D2 · D22).
 * A partial purchase is split into several rows; a wrong quantity or amount is corrected with a negative row. A
 * receipt header edit rewrites rows: every line of that receipt takes the new seller and purchase day
 * (FEATURE_2610_18 D100).
 *
 * <p>⚠️ The physical-side ledger is {@link StockMovement} (D1). One [입고] writes both ledgers in one transaction
 * while the stock link setting is on (FEATURE_2610_09 D65) — {@link #stockLinked} records which mode applied.
 *
 * <p>⚠️ ddl-auto=validate (prod) → the @Column definitions below must match the purchase_record DDL
 * (changesets 077 · 109-4 · 110-3).
 *
 * @see com.pms.domain.StockMovement physical stock ledger
 */
@Entity
@Table(name = "purchase_record")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PurchaseRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 매입한 물품(구성품). 집계 그룹 키 (PLAN 2609_29 D3·D6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 이 매입이 누구 것인가 (PLAN 2609_29 D3). 정산 귀속의 근거이므로 유추가 아니라 컬럼이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    /** 구매 날짜 (통계용). */
    @Column(name = "purchased_on", nullable = false)
    private LocalDate purchasedOn;

    /** 그날 구매 수량. 정정 시 음수 허용. */
    @Column(nullable = false)
    private Integer quantity;

    /** Actually paid amount for this purchase line. SOURCE OF TRUTH (PLAN 2609_28 D1). */
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /** Derived: totalAmount / quantity, scale 4. Stored so FIFO and propagation read it directly. */
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * Whether this purchase should move Product.price (PLAN 2609_28 D3).
     * false = promotional / one-off buy: the real cost still drives this order's margin,
     * but the base selling price must not follow it.
     */
    @Column(name = "reflect_to_base_price", nullable = false)
    private Boolean reflectToBasePrice;

    /**
     * Stock link setting when this purchase was saved (FEATURE_2610_09 D84). false = saved while the setting was
     * off: it never shows in the check-in waiting list, even after the setting is turned on (D71 ④).
     */
    @Builder.Default
    @Column(name = "stock_linked", nullable = false)
    private Boolean stockLinked = Boolean.TRUE;

    /**
     * The receipt this purchase came in with (FEATURE_2610_18 D28 · D93 ①). Null = saved without a receipt photo —
     * the per-product check-in (D75).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private PurchaseReceipt receipt;

    /**
     * 이 매입이 {@code Product.price}(기준 원가)를 움직이는가 — <b>이 판정의 유일한 자리</b>
     * (FEATURE_2609_28 / PLAN D3·D4 ①).
     *
     * <p>세 조건 전부여야 한다:
     * <ul>
     *   <li>{@code reflectToBasePrice} — 프로모션·1회성 매입은 기준가를 움직이면 안 된다(D3)</li>
     *   <li>{@code unitPrice != null} — 금액 미상 매입은 기준가의 근거가 못 된다</li>
     *   <li>{@code quantity > 0} — 정정(음수) 행은 기준가를 정의하지 않는다</li>
     * </ul>
     *
     * <p>🔴 갱신하는 쪽({@code CostPropagationService.updateBasePrice})과 파급 대상을 찾는 쪽
     * ({@code preview})이 <b>같은 이 메서드</b>를 쓴다. 조건을 한쪽에만 적어 두면 "기준가는 갱신됐는데
     * 파급 대상에는 안 뜨는" 유령 상품이 생긴다.
     */
    public boolean movesBasePrice() {
        return Boolean.TRUE.equals(reflectToBasePrice)
                && unitPrice != null
                && quantity != null && quantity > 0;
    }

    /** total_amount scale — KRW actually paid (PLAN 2609_28 D1). */
    private static final int TOTAL_SCALE = 2;
    /** unit_price scale — 10,000 / 3 must not collapse to 3,333.33 (PLAN 2609_28 D1). */
    private static final int UNIT_SCALE = 4;

    /**
     * Single place where the purchase amount rules live (PLAN 2609_28 D1·D2).
     * Web, mobile and any future bulk input must go through here so nobody rounds differently.
     *
     * <p>Amount input is either total OR unit price, never both — otherwise it is ambiguous which one
     * is the truth. The missing side is derived:
     * <ul>
     *   <li>total given -> unitPrice = total / quantity (scale 4, HALF_UP)</li>
     *   <li>unit given  -> totalAmount = unit * quantity (scale 2, HALF_UP)</li>
     *   <li>neither given -> both stay null ("amount unknown"; never substitute zero)</li>
     * </ul>
     *
     * <p>⚠️ quantity is signed: a correction row is negative and its amount is negative too, so the
     * derived unit price stays positive (−12,000 / −3 = 4,000). quantity 0 is rejected (division).
     *
     * @throws IllegalArgumentException quantity == 0, or both amounts supplied (→ 400 via GlobalExceptionHandler)
     */
    public static PurchaseRecord of(Product product, Seller seller, PurchaseRecordRequest request) {
        int quantity = request.quantity();
        BigDecimal total = request.totalAmount();
        BigDecimal unit = request.unitPrice();
        if (quantity == 0) {
            throw new IllegalArgumentException("수량은 0일 수 없습니다");
        }
        if (total != null && unit != null) {
            throw new IllegalArgumentException("총액과 단가 중 하나만 입력하세요");
        }
        BigDecimal resolvedUnit = null;
        BigDecimal resolvedTotal = null;
        if (total != null) {
            resolvedTotal = total.setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
            resolvedUnit = total.divide(BigDecimal.valueOf(quantity), UNIT_SCALE, RoundingMode.HALF_UP);
        } else if (unit != null) {
            resolvedUnit = unit.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
            resolvedTotal = unit.multiply(BigDecimal.valueOf(quantity)).setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
        }
        return PurchaseRecord.builder()
                .product(product)
                .seller(seller)
                .purchasedOn(request.purchasedOn())
                .quantity(quantity)   // 음수 허용(정정)
                .totalAmount(resolvedTotal)
                .unitPrice(resolvedUnit)
                .reflectToBasePrice(request.reflectToBasePrice() == null || request.reflectToBasePrice())
                .build();
    }
}
