package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;

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
 * <p>⚠️ <b>Nothing automatic writes here</b> (D18). Sync jobs, schedulers and shipment hooks must not
 * call the ledger service — the only caller is the controller. If a hook ever writes a row, the
 * ledger stops matching the warehouse and the whole feature loses its meaning.
 *
 * <p>⚠️ {@code movedOn} (the day a person confirmed the goods moved) and {@code createdAt} (the audit
 * timestamp of the row) are <b>different</b>. Yesterday's delivery can be entered this morning.
 *
 * <p>⚠️ No UNIQUE constraint: the same product moves many times a day and corrections pile up as
 * extra rows. Negative balances are allowed on purpose — a negative number means "an entry is
 * missing", which is a signal, not an error to reject (rejecting it makes people stop using the
 * ledger at all).
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions below must match changeset 071.
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
     * PURCHASE = copied from {@code purchase_record.unitPrice}.
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

    /** Required for STOCK_IN + PURCHASE — the money-side row this delivery fulfils. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_record_id")
    private PurchaseRecord purchaseRecord;

    /** The day a person confirmed the goods moved. NOT the audit timestamp. */
    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;

    /** Who confirmed it (authentication principal = email). Filled by the server, never by the request (D9). */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
