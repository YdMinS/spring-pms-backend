package com.pms.domain;

import com.pms.dto.request.PurchaseRecordRequest;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 매입 이력 (물품 × 판매자). 돈 쪽 원장.
 *
 * <p>🔴 주문 라인에 묶지 않는다(PLAN 2609_29 D3). 입고는 "이 주문 몫"이 아니라 "이 판매자가 이 물품을
 * 이만큼 들였다"이다 — 주문이 없거나(수동 보충) 취소돼 사라져도 매입 귀속은 남아야 정산이 선다.
 *
 * <p>물품 구매수량 = SUM(quantity) (전체 기준, 판매자로 쪼개지 않는다 — D6).
 * 잔여 = 필요수량 − 구매수량. 부분구매는 수량을 나눠 여러 행으로, 오입력 정정은 음수 행으로 쌓는다.
 *
 * <p>⚠️ 실물 쪽 원장은 {@link StockMovement} 다(D1). [입고] 1회가 두 원장을 같은 트랜잭션에서 쓴다 —
 * 여기만 저장하고 끝내면 "돈은 나갔는데 물건은 모르는" 행이 남는다.
 *
 * <p>⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 purchase_record DDL(changeset 077)과 일치해야 한다.
 *
 * @see com.pms.domain.StockMovement 실물 재고 원장
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
