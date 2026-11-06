package com.pms.domain;

import com.pms.dto.request.PurchaseRecordRequest;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Purchase history (product × seller) — the money-side ledger.
 *
 * <p>🔴 Not tied to an order line (PLAN 2609_29 D3). A check-in means "this seller brought in this many of this
 * product", not "this order's share" — the attribution must survive when there is no order (manual restock) or the
 * order is cancelled, or settlement breaks.
 *
 * <p>Purchased quantity on the purchase list = SUM(quantity) per (product × seller) (FEATURE_2610_09 D2 · D22).
 * A partial purchase is split into several rows; a wrong quantity is corrected with a negative row. A receipt header
 * edit rewrites rows: every line of that receipt takes the new seller and purchase day (FEATURE_2610_18 D100). The
 * amount of a decided purchase is retyped in place on the debt screen (FEATURE_2610_19 D141) and by
 * 「일별 입출고 내역」 [매입가 넣기] (FEATURE_2611_16 D1 · D6 · D17) — quantity, seller and purchase day stay.
 *
 * <p>⚠️ The physical-side ledger is {@link StockMovement} (D1). One [입고] writes both ledgers in one transaction
 * while the stock link setting is on (FEATURE_2610_09 D65) — {@link #stockLinked} records which mode applied.
 *
 * <p>⚠️ ddl-auto=validate (prod) → the @Column definitions below must match the purchase_record DDL
 * (changesets 077 · 109-4 · 110-3 · 122-2 · 123-2).
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

    /**
     * Whose purchase this is (PLAN 2609_29 D3) — a column, not an inference, because settlement hangs on it.
     * null = "seller undecided" (FEATURE_2610_19 D105): an intake-work purchase whose receipt is not assigned yet, and
     * the minus rows that take assigned quantity off it (D130 ②). Undecided purchases count for no seller (D115).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id")
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
     * The receipt this purchase came in with (FEATURE_2610_18 D28 · D93 ①). Null = no receipt — the per-product
     * check-in (D75) or a 「영수증 없음」 assignment (FEATURE_2611_01 D4). A 「영수증 나중에」 assignment points at a
     * receipt that has no photo yet. 「매입가 넣기」 with a photo sets it later on a purchase without one
     * (FEATURE_2611_16 D2).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private PurchaseReceipt receipt;

    /**
     * The undecided intake row this purchase was assigned from (FEATURE_2610_19 D130 ①). Set = an assignment — with a
     * receipt, 「영수증 나중에」 or 「영수증 없음」 (FEATURE_2611_01 D2): the goods are already in stock, so it never waits
     * for check-in and has no stock row of its own.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_movement_id")
    private StockMovement sourceMovement;

    /** Set when the empty unit price was typed later on the debt screen (FEATURE_2610_19 D141 · D143 ①). */
    @Column(name = "price_filled_at")
    private LocalDateTime priceFilledAt;

    /**
     * Set = a 「영수증 없음(발견 재고)」 assignment (FEATURE_2611_01 D12): it counts as purchased quantity (D9) and its
     * amount feeds cost and debts (D5), but a purchase amount total must leave it out. It stays when the seller is
     * changed later (D7) and goes when 「매입가 넣기」 binds the purchase to a receipt with its photo (FEATURE_2611_16
     * D2).
     */
    @Column(name = "found_stock_at")
    private LocalDateTime foundStockAt;

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
     * The purchase amount rules live in this class only (PLAN 2609_28 D1·D2): this factory, plus
     * {@link #assignedParts} and {@link #assignmentMinus} for a receipt assignment (FEATURE_2610_19 D135 ① round 32),
     * {@link #withTypedUnitPrice} · {@link #typedUnit} · {@link #registeredPriceOf} for a unit price typed later on
     * the debt screen (D141 ② · D145 · D146), and {@link #withRetypedAmount} · {@link #totalOf} for 「매입가 넣기」 and
     * the daily view's amounts (FEATURE_2611_16 D6 · D8).
     * Web, mobile and any future bulk input must go through these methods so nobody rounds differently.
     *
     * <p>Amount input is either total OR unit price, never both — otherwise it is ambiguous which one
     * is the truth. The missing side is derived:
     * <ul>
     *   <li>total given -> unitPrice = total / quantity (scale 4, HALF_UP)</li>
     *   <li>unit given  -> totalAmount = unit * quantity (scale 2, HALF_UP)</li>
     *   <li>neither given, quantity &gt; 0 -> the product's registered price at this moment is the unit price and the
     *       base price is not moved (FEATURE_2610_19 D135 ① ②); no registered price -> both stay null ("amount
     *       unknown"; never substitute zero). A minus row is never filled (D135 ③).</li>
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
        boolean reflect = request.reflectToBasePrice() == null || request.reflectToBasePrice();
        if (total == null && unit == null && quantity > 0 && product.getPrice() != null) {
            unit = product.getPrice();
            reflect = false;
        }
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
                .reflectToBasePrice(reflect)
                .build();
    }

    /**
     * Receipt assignment (FEATURE_2610_19 D130 ① · D135 ① round 32 · PLAN R4): one record of the payer seller per
     * undecided intake row a line is taken from, in the order of {@code parts}.
     *
     * <ul>
     *   <li>total given → one unit price for every part = total / line quantity (scale 4, HALF_UP); each part's total =
     *       unit × part quantity (scale 2, HALF_UP), the last part takes what is left of the total — the parts add up to
     *       the total typed</li>
     *   <li>unit given → each part's total = unit × part quantity (scale 2, HALF_UP)</li>
     *   <li>neither given → the product's registered price at this moment, base price not moved (D135 ① ②); no
     *       registered price → both stay null and the reflect choice stays as sent (as in {@link #of})</li>
     * </ul>
     *
     * @param parts the intake row and the quantity taken from it, per part; quantities add up to the line quantity
     */
    public static List<PurchaseRecord> assignedParts(Product product, Seller payer, LocalDate purchasedOn,
                                                     BigDecimal totalAmount, BigDecimal unitPrice,
                                                     Boolean reflectToBasePrice, PurchaseReceipt receipt,
                                                     List<Map.Entry<StockMovement, Integer>> parts) {
        if (totalAmount != null && unitPrice != null) {
            throw new IllegalArgumentException("총액과 단가 중 하나만 입력하세요");
        }
        int quantity = parts.stream().mapToInt(Map.Entry::getValue).sum();
        boolean typed = totalAmount != null || unitPrice != null;
        BigDecimal unit = totalAmount != null
                ? totalAmount.divide(BigDecimal.valueOf(quantity), UNIT_SCALE, RoundingMode.HALF_UP)
                : (unitPrice != null ? unitPrice : product.getPrice());
        boolean filled = !typed && unit != null;
        boolean reflect = !filled && (reflectToBasePrice == null || reflectToBasePrice);
        BigDecimal totalLeft = totalAmount;
        List<PurchaseRecord> records = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            int part = parts.get(index).getValue();
            boolean last = index == parts.size() - 1;
            BigDecimal partTotal;
            if (unit == null) {
                partTotal = null;
            } else if (totalLeft != null && last) {
                partTotal = totalLeft.setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
            } else {
                partTotal = unit.multiply(BigDecimal.valueOf(part)).setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
            }
            if (totalLeft != null) {
                totalLeft = totalLeft.subtract(partTotal);
            }
            records.add(PurchaseRecord.builder()
                    .product(product).seller(payer).purchasedOn(purchasedOn).quantity(part)
                    .totalAmount(partTotal)
                    .unitPrice(unit == null ? null : unit.setScale(UNIT_SCALE, RoundingMode.HALF_UP))
                    .reflectToBasePrice(reflect).stockLinked(true).receipt(receipt)
                    .sourceMovement(parts.get(index).getKey())
                    .build());
        }
        return records;
    }

    /**
     * The minus row an assignment adds to the undecided record (FEATURE_2610_19 D130 ② · D135 ③): the same unit price,
     * total = unit × −quantity (scale 2, HALF_UP), the undecided record's purchase day, base price not moved.
     */
    public static PurchaseRecord assignmentMinus(PurchaseRecord undecided, int quantity) {
        BigDecimal unit = undecided.getUnitPrice();
        return PurchaseRecord.builder()
                .product(undecided.getProduct()).seller(null).purchasedOn(undecided.getPurchasedOn())
                .quantity(-quantity)
                .totalAmount(unit == null ? null
                        : unit.multiply(BigDecimal.valueOf(-quantity)).setScale(TOTAL_SCALE, RoundingMode.HALF_UP))
                .unitPrice(unit).reflectToBasePrice(false).stockLinked(true)
                .build();
    }

    /**
     * A unit price typed later on the debt screen (FEATURE_2610_19 D141 ② · D143): unit (scale 4, HALF_UP), total =
     * unit × this record's quantity (scale 2, HALF_UP). Quantity, seller and purchase day stay.
     */
    public PurchaseRecord withTypedUnitPrice(BigDecimal typedUnitPrice, boolean reflect, LocalDateTime filledAt) {
        BigDecimal unit = typedUnit(typedUnitPrice);
        return toBuilder()
                .unitPrice(unit)
                .totalAmount(unit.multiply(BigDecimal.valueOf(quantity)).setScale(TOTAL_SCALE, RoundingMode.HALF_UP))
                .reflectToBasePrice(reflect)
                .priceFilledAt(filledAt)
                .build();
    }

    /** This assignment as found stock, marked at {@code at} (FEATURE_2611_01 D12). */
    public PurchaseRecord asFoundStock(LocalDateTime at) {
        return toBuilder().foundStockAt(at).build();
    }

    /**
     * A 「영수증 없음」 share whose seller 「판매자 바꾸기」 may change (FEATURE_2611_01 D15): an assignment taken from an
     * undecided intake row without a receipt. A 「영수증 나중에」 share has its receipt and changes by the receipt edit.
     */
    public boolean isNoReceiptShare() {
        return sourceMovement != null && receipt == null && seller != null && quantity != null && quantity > 0;
    }

    /**
     * Which receipt this purchase is a line of (FEATURE_2611_16 D8 · D13 · D18): none · a 「영수증 나중에」 receipt that
     * has no photo yet · a receipt with its photo.
     */
    public enum ReceiptKind { NO_RECEIPT, RECEIPT_LATER, RECEIPT }

    /** This purchase's {@link ReceiptKind}. */
    public ReceiptKind receiptKind() {
        if (receipt == null) {
            return ReceiptKind.NO_RECEIPT;
        }
        return receipt.getPhotoUrl() == null ? ReceiptKind.RECEIPT_LATER : ReceiptKind.RECEIPT;
    }

    /**
     * A purchase whose amount 「매입가 넣기」 may retype (FEATURE_2611_16 D1 · D17): a decided purchase that came into
     * stock — a check-in with its stock row or an assignment from an undecided intake row, with or without a receipt.
     * An undecided purchase (no seller), a minus row and a purchase saved with the stock link off are not.
     */
    public boolean isPriceRetypable() {
        return seller != null && quantity != null && quantity > 0 && Boolean.TRUE.equals(stockLinked);
    }

    /**
     * The amount retyped by 「매입가 넣기」 (FEATURE_2611_16 D6 · D7): total given → unit = total / quantity (scale 4,
     * HALF_UP), total at scale 2 · unit given → total = unit × quantity (scale 2, HALF_UP). Quantity, seller, purchase
     * day, receipt and the debt screen mark stay.
     *
     * @throws IllegalArgumentException both amounts or neither given (→ 400 via GlobalExceptionHandler)
     */
    public PurchaseRecord withRetypedAmount(BigDecimal total, BigDecimal unit, boolean reflect) {
        if (total != null && unit != null) {
            throw new IllegalArgumentException("총액과 단가 중 하나만 입력하세요");
        }
        if (total == null && unit == null) {
            throw new IllegalArgumentException("총액 또는 단가를 입력하세요");
        }
        BigDecimal resolvedUnit = total != null
                ? total.divide(BigDecimal.valueOf(quantity), UNIT_SCALE, RoundingMode.HALF_UP)
                : unit.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal resolvedTotal = total != null
                ? total.setScale(TOTAL_SCALE, RoundingMode.HALF_UP)
                : totalOf(unit, quantity);
        return toBuilder().unitPrice(resolvedUnit).totalAmount(resolvedTotal).reflectToBasePrice(reflect).build();
    }

    /**
     * This purchase as a line of {@code lineReceipt} (FEATURE_2611_16 D2): the 「발견 재고」 mark goes — a line of a
     * receipt with its photo counts as a purchase.
     */
    public PurchaseRecord asReceiptLine(PurchaseReceipt lineReceipt) {
        return toBuilder().receipt(lineReceipt).foundStockAt(null).build();
    }

    /**
     * FEATURE_2610_19 D144 ①: no other purchase in {@code movers} (the product's purchases that move the registered
     * price) is later than {@code record} — purchase day, then record order. The seller does not matter.
     */
    public static boolean isLatestMover(PurchaseRecord record, List<PurchaseRecord> movers) {
        for (PurchaseRecord other : movers) {
            if (other.getId().equals(record.getId())) continue;
            int byDay = other.getPurchasedOn().compareTo(record.getPurchasedOn());
            if (byDay > 0 || (byDay == 0 && other.getId() > record.getId())) {
                return false;
            }
        }
        return true;
    }

    /** unit × quantity at the total scale (2, HALF_UP) — the daily view's undecided amount uses it too (D8). */
    public static BigDecimal totalOf(BigDecimal unit, int quantity) {
        return unit.multiply(BigDecimal.valueOf(quantity)).setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
    }

    /** A typed unit price at the stored scale (4, HALF_UP) — the lot rows without a purchase record use it too (D141 ①). */
    public static BigDecimal typedUnit(BigDecimal typedUnitPrice) {
        return typedUnitPrice.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
    }

    /** A unit price as a registered price (scale 2, HALF_UP — the same scale the base price update uses, D145 · D146). */
    public static BigDecimal registeredPriceOf(BigDecimal unitPrice) {
        return unitPrice.setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
    }
}
