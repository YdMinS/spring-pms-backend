package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Physical stock ledger — one row per HUMAN-CONFIRMED movement (FEATURE_2609_28 / PLAN D5~D10, D14).
 *
 * <p>This table exists because {@code purchase_record} answers a different question. That one says
 * "money was spent"; this one says "the goods are actually here". Bought on 10/2, arrived 10/5 —
 * between those dates the warehouse is empty, so a purchase row must not raise the balance.
 *
 * <p><b>The balance is derived</b>, not stored: {@code SUM(quantity)} grouped by product (D14).
 * There is deliberately no stock column on {@link Product} — a state column drifts, needs hooks and
 * locks, and cannot be repaired once wrong; a ledger can always be recomputed.
 *
 * <p>⚠️ <b>Nothing automatic writes here</b> (2609_28 D18, revised by PLAN 2609_29 D2): no row may be
 * written without a person confirming it. Sync jobs, schedulers and shipment hooks are still
 * forbidden; the [입고] click on the purchase list IS that confirmation, so
 * {@code PurchaseListService -> StockLedgerService} is the one opened path. A receipt header edit
 * (FEATURE_2610_18 D101) writes no row — it only moves the rows of its lines, and every row that used them
 * (FEATURE_2610_19 D149), to the new payer seller, and the rows of its lines to a new purchase day
 * (FEATURE_2611_21 D26). Filling an empty source later (FEATURE_2611_22 D3) is a person's [채우기] click too.
 *
 * <p>⚠️ {@code movedOn} (the day a person confirmed the goods moved) and {@code createdAt} (the audit
 * timestamp of the row) are <b>different</b>. Yesterday's delivery can be entered this morning.
 *
 * <p>⚠️ No UNIQUE constraint: the same product moves many times a day and corrections pile up as
 * extra rows. Negative balances are allowed on purpose — a negative number means "an entry is
 * missing", which is a signal, not an error to reject (rejecting it makes people stop using the
 * ledger at all). An order outbound is never rejected for it; with the stock link on, a disposal or an adjust − is
 * rejected when it takes more than is left (FEATURE_2611_22 D34).
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions below must match changesets 071 + 078 + 122-1 + 129-1 + 131-1.
 *
 * @see StockMovementType movement kinds and their sign
 * @see StockReason reason codes and which type each belongs to
 */
@Entity
@Table(name = "stock_movement")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class StockMovement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The physical item that moved. Always the purchasable unit (BOM component), never an option. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Whose stock moved (PLAN 2609_29 D4). Stock is NOT shared between sellers — without this axis
     * the purchase cost and the on-hand balance cannot be attributed to anyone, and using A's goods
     * for B's order becomes invisible instead of an internal transfer.
     *
     * <p>Derived by the server for {@code STOCK_OUT} (the owner of the lot it used, no lot = the order's seller,
     * FEATURE_2610_19 D124) and {@code RETURN_IN} (claim -> order -> account -> seller, D22; an order-unmatched
     * claim -> its sales channel's seller, FEATURE_2610_19 D140); required in the request for every other type
     * except an intake-work row (null, below).
     *
     * <p>null = "seller undecided" (FEATURE_2610_19 D105): an intake-work row whose receipt is not assigned yet, and
     * an outbound row that used such a row (D124). An undecided row is never edited to get an owner — its owners are
     * the assigned purchase records pointing at it (D130) and the split of its outgoing rows (D131).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id")
    private Seller seller;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private StockMovementType movementType;

    /** Signed quantity — the SERVER decides the sign, never the client (a disposal of 3 is stored as -3). */
    @Column(nullable = false)
    private Integer quantity;

    /** Resolved by {@code resolveLocation} only (D17). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockLocation location;

    /** Required for STOCK_IN / DISPOSAL / ADJUST, not accepted for STOCK_OUT / RETURN_IN (D7). */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private StockReason reason;

    /** Mandatory when {@code reason == ETC} — otherwise the row cannot be explained later. */
    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    /**
     * Cost carried by this movement (D8): OPENING = {@code Product.price} snapshot, FREE = 0,
     * PURCHASE = copied from {@code purchase_record.unitPrice}; a PURCHASE without a purchase record (stock
     * link off, FEATURE_2610_09 D71 round 13) = the typed price, else the {@code Product.price} snapshot.
     *
     * <p>FEATURE_2610_19 (stock link on): adjust + and an order-unmatched return = the {@code Product.price}
     * snapshot (R15 · D140); a matched return follows the lot it used (D139); an empty lot price can be typed later
     * on the debt screen (D141).
     *
     * <p>FEATURE_2611_17: an ETC stock-in without a typed price = the {@code Product.price} snapshot (D25); an empty
     * lot price is filled from {@code Product.price} once the product has one (D22) — both set
     * {@link #priceFromBaseAt}. An opening-stock or 「기타」 intake price can be typed again in the daily row popup (D17 ·
     * FEATURE_2611_22 D38).
     *
     * <p>null is a legitimate value meaning "amount unknown" — never substituted with 0.
     */
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** Only STOCK_OUT carries it (prompt 05). Nullable at DB level; required per type in the service. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;

    /** Required for RETURN_IN — the claim the goods came back from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_claim_id")
    private OrderClaim orderClaim;

    /**
     * Required for STOCK_IN + PURCHASE while the stock link is on (off = may be null, FEATURE_2610_09 D71
     * round 12) — the money-side row this delivery fulfils.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_record_id")
    private PurchaseRecord purchaseRecord;

    /**
     * The lot (incoming row) this row used (FEATURE_2610_19 D42): an outbound, disposal or adjust− row takes goods
     * out of it, a matched return row puts goods back into it (D139 ②). null = no lot was used (D48 ③) or the row is
     * itself a lot. Written once and never re-pointed (D125); an order outbound row without one may get it once later
     * ({@code StockFillService}, FEATURE_2611_22 D9), then it stays.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_movement_id")
    private StockMovement sourceMovement;

    /** Set when the empty unit price of this lot was typed later on the debt screen (FEATURE_2610_19 D141 · D143 ①). */
    @Column(name = "price_filled_at")
    private LocalDateTime priceFilledAt;

    /** Set when that typed price also filled the product's empty registered price (FEATURE_2610_19 D145 · D146 ①). */
    @Column(name = "base_price_filled_at")
    private LocalDateTime basePriceFilledAt;

    /**
     * Set when the empty unit price was filled from the product's registered price (FEATURE_2611_17 D15 · D22 · D25):
     * the daily view shows 「미기입」 next to the value. A price typed later clears it.
     */
    @Column(name = "price_from_base_at")
    private LocalDateTime priceFromBaseAt;

    /**
     * Set when this order outbound row got its lot later — 「○월 ○일 입고로 나중에 채움」 (FEATURE_2611_22 D9). null = the
     * row was written with its lot, or still has none.
     */
    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    /** Who filled it (authentication principal = email, FEATURE_2611_22 D9); null with {@link #filledAt}. */
    @Column(name = "filled_by", length = 100)
    private String filledBy;

    /**
     * The day a person confirmed the goods moved. NOT the audit timestamp. An admin may correct it later in
     * 「일별 입출고 내역」 — {@code StockDateEditService} only, each correction kept in {@link StockMovementDateEdit}
     * (FEATURE_2611_21 D1 · D7 · D11).
     */
    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;

    /** Who confirmed it (authentication principal = email). Filled by the server, never by the request (D9). */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * An incoming row 「판매자 바꾸기」 may move (FEATURE_2611_01 D7 · D15): a lot with a decided seller — a stock-in
     * (purchase, opening, free, etc.) or an adjust + — that no receipt is tied to. A return, an undecided row, a
     * receipt line and an outgoing row are not.
     */
    public boolean isSellerChangeableWithoutReceipt() {
        boolean incoming = movementType == StockMovementType.STOCK_IN || movementType == StockMovementType.ADJUST;
        return incoming && quantity != null && quantity > 0 && sourceMovement == null && seller != null
                && (purchaseRecord == null || purchaseRecord.getReceipt() == null);
    }

    /**
     * An incoming row whose purchase 「매입가 넣기」 may retype (FEATURE_2611_16 D1 · D17): a stock-in with a decided
     * seller that fulfils a purchase record — with or without a receipt. An undecided row, opening stock, free or
     * etc. intake, an adjust +, a return and an outgoing row are not.
     */
    public boolean isPriceRetypable() {
        return movementType == StockMovementType.STOCK_IN && quantity != null && quantity > 0
                && sourceMovement == null && seller != null
                && purchaseRecord != null && purchaseRecord.isPriceRetypable();
    }

    /**
     * An opening-stock or 「기타」 intake row whose unit price the daily row popup may set (FEATURE_2611_17 D5 · D17 ·
     * FEATURE_2611_22 D38): a stock-in of 「기초재고」 or 「기타」 with a decided seller, even when it already has a price.
     */
    public boolean isOpeningPriceEditable() {
        return movementType == StockMovementType.STOCK_IN
                && (reason == StockReason.OPENING || reason == StockReason.ETC)
                && quantity != null && quantity > 0 && sourceMovement == null && seller != null;
    }

    /**
     * A lot that may fill an empty order outbound row (FEATURE_2611_22 D4 · D18): a stock-in (purchase, opening, free,
     * etc.) or an adjust +, not a return and not a return into another lot.
     */
    public boolean isFillLot() {
        boolean incoming = movementType == StockMovementType.STOCK_IN || movementType == StockMovementType.ADJUST;
        return incoming && quantity != null && quantity > 0 && sourceMovement == null;
    }

    /**
     * An order outbound row still without a lot that may be filled (FEATURE_2611_22 D11 · D16 · D28): written at or
     * after {@code stockLinkedAt}, the last time the stock link was turned on (null = on from the start). The caller
     * checks that the stock link is on now.
     */
    public boolean isFillableOutbound(LocalDateTime stockLinkedAt) {
        return movementType == StockMovementType.STOCK_OUT && quantity != null && quantity < 0
                && sourceMovement == null && orderLine != null
                && (stockLinkedAt == null || (getCreatedAt() != null && !getCreatedAt().isBefore(stockLinkedAt)));
    }

    /** The row got its lot later (FEATURE_2611_22 D9). */
    public boolean isFilledLater() {
        return filledAt != null;
    }

    /** Whether the unit price was filled from the registered price — the 「미기입」 mark (FEATURE_2611_17 D22). */
    public boolean isPriceFromBase() {
        return priceFromBaseAt != null;
    }

    /**
     * This lot with its empty unit price filled from the registered price (FEATURE_2611_17 D22): unit = that price at
     * the stored scale (4, HALF_UP), the 「미기입」 mark set at {@code at}.
     */
    public StockMovement withBasePriceFill(BigDecimal registeredPrice, LocalDateTime at) {
        return toBuilder().unitPrice(PurchaseRecord.typedUnit(registeredPrice)).priceFromBaseAt(at).build();
    }
}
