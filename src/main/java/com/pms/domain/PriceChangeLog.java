package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * One price movement — cost or selling price (FEATURE_2609_28 / PLAN D23).
 *
 * <p>This table exists because neither price is recoverable afterwards. The selling price is
 * overwritten in place, and the cost is only <em>partly</em> reconstructable from
 * {@code purchase_record}: a direct {@code PATCH /api/products/{id}} edit leaves no trace at all.
 * A margin alert built later can then only answer "is the margin thin now", never "since when".
 *
 * <p>⚠️ <b>Movements only.</b> A first price (channel add, import, option add) is not written here —
 * a row with no previous value cannot explain a change and only inflates the list. The two skip
 * rules ({@code oldPrice == null}, {@code DRAFT} cell) live in {@code PriceHistoryRecorder}.
 *
 * <p>⚠️ Exactly ONE of {@link #product} / {@link #listingOption} is set, decided by
 * {@link #targetType}. The DB cannot express that; the recorder is the single writer that does.
 *
 * <p>⚠️ The channel of a selling-price row ({@code listingId}, {@code platform},
 * {@code masterProductId}) is NOT stored here. It is joined through
 * {@code product_listing_option -> product_listing} at read time: copied into this row it would go
 * stale as soon as the cell is moved to another master.
 *
 * <p>⚠️ No UNIQUE constraint — the same option is repriced many times, and each time is a row.
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions must match changeset 082.
 *
 * @see PriceTargetType which price moved
 * @see PriceChangeReason why it moved
 */
@Entity
@Table(name = "price_change_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PriceChangeLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private PriceTargetType targetType;

    /** Set for {@link PriceTargetType#PRODUCT_COST} only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = true)
    private Product product;

    /** Set for {@link PriceTargetType#LISTING_SELLING} only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_option_id", nullable = true)
    private ProductListingOption listingOption;

    /**
     * The value before the change. <b>NOT NULL</b>: a change with no previous value is a first
     * setting, and those are not recorded at all (see the class note). Nullable here would quietly
     * re-open "creation is also recorded".
     *
     * <p>scale 4 = the purchase unit price scale, so a cost row is stored exactly as computed
     * ({@code total / qty}) and never rounded twice.
     */
    @Column(name = "old_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal newPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PriceChangeReason reason;

    /**
     * The purchase that moved the cost ({@link PriceChangeReason#PURCHASE_UPDATE}), so a base price
     * links back to the money actually spent.
     *
     * <p>⚠️ Stored as a raw id, not an association: the recorder only ever knows the id, and a LAZY
     * {@code @ManyToOne} would add a proxy without adding information. The DB-level FK (changeset
     * 082) still guards integrity.
     */
    @Column(name = "purchase_record_id")
    private Long purchaseRecordId;

    /** Who changed it (authentication principal = e-mail). Null in a batch/unauthenticated context. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
