package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One revenue (or refund) line the marketplace confirmed — platform-neutral core (PLAN 2609_30 D5·D7·D10).
 *
 * <p>This is the only measured material we have for reconciliation and profit: it carries the fee the
 * platform actually charged, per order line. Platform-specific fee columns and the raw response live
 * in {@link CoupangSettlementLine} (1:1), same split as {@code order_line}/{@code coupang_order_line}.</p>
 *
 * <p>🔴 <b>{@link #orderLine} may be null and that is a NORMAL state</b> (D7), not a defect: ad-cost
 * offsets and orders outside our loaded window can never match. Dropping unmatched lines would make
 * the totals disagree with the platform, and totals that disagree destroy trust in the whole screen.
 * Unmatched lines are stored and counted, never thrown away.</p>
 *
 * <p>🔴 <b>{@link #settlementPayout} is null after prompt 01</b> (D5-2) — the revenue-history feed has
 * no settlementType, so guessing the payout grouping here would not line up with the bank transfer.
 * Prompt 02 attributes lines to payouts; the upsert must never clear that attribution.</p>
 *
 * <p>🔴 Unique key = (account, externalOrderId, platformOptionId, saleType, recognitionDate) — it
 * deliberately excludes {@code settlement_payout_id}, which is null at load time. Corrections are
 * applied by UPDATE, never delete-insert: a delete-insert wipes the order-line mapping and the payout
 * attribution (D10).</p>
 *
 * <p>⚠️ Amounts are stored exactly as the platform sent them (all positive). The direction lives in
 * {@link #saleType}.</p>
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → these @Column definitions must match changeset 083.</p>
 */
@Entity
@Table(name = "settlement_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_settlement_line",
                columnNames = {"marketplace_account_id", "external_order_id", "platform_option_id",
                        "sale_type", "recognition_date"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SettlementLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Nullable until prompt 02 attributes the line to a payout unit (D5-2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_payout_id")
    private SettlementPayout settlementPayout;

    /** The only owner axis available while {@link #settlementPayout} is still null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /** Null = UNMATCHED. See the class javadoc — this is a normal state, not a failure (D7). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_option_id")
    private ProductListingOption productListingOption;

    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId;

    /** Coupang vendorItemId. Called by its neutral name here (2609_26 naming rule). */
    @Column(name = "platform_option_id", nullable = false, length = 100)
    private String platformOptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", length = 10)
    private SaleType saleType;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "sale_amount", precision = 15, scale = 2)
    private BigDecimal saleAmount;

    /** Stored positive; the direction is {@link #saleType}'s job. */
    @Column(name = "service_fee", precision = 15, scale = 2)
    private BigDecimal serviceFee;

    @Column(name = "service_fee_vat", precision = 15, scale = 2)
    private BigDecimal serviceFeeVat;

    /**
     * Measured commission rate, VAT excluded (Coupang: "Service fee rate (%, VAT excluded)").
     * Prompt 06 reads this to propose {@code PlatformCategory.commissionRate} — proposal only (D16).
     */
    @Column(name = "service_fee_ratio", precision = 7, scale = 4)
    private BigDecimal serviceFeeRatio;

    /** Seller-funded coupons, summed. */
    @Column(name = "coupon_amount", precision = 15, scale = 2)
    private BigDecimal couponAmount;

    /** Delivery-fee share, remote-area surcharge included. */
    @Column(name = "delivery_fee_amount", precision = 15, scale = 2)
    private BigDecimal deliveryFeeAmount;

    @Column(name = "settlement_amount", precision = 15, scale = 2)
    private BigDecimal settlementAmount;

    /** Revenue-recognition date = the axis both settlement APIs are keyed on. Part of the unique key. */
    @Column(name = "recognition_date", nullable = false)
    private LocalDate recognitionDate;

    @Column(name = "sale_date")
    private LocalDate saleDate;
}
