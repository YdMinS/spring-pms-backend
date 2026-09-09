package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One payout unit mirrored from the marketplace (PLAN 2609_30 D5).
 *
 * <p>Owned by a marketplace ACCOUNT, not a seller: settlement arrives per vendorId, and each channel
 * has its own cycle (settlementType). A seller-level total is a query-time sum, never a stored row (D3).</p>
 *
 * <p>⚠️ Immutable ledger. We never write our own computed numbers into these rows — reconciliation
 * results live in reconStatus and in {@code settlement_adjustment} (D9), not in the mirrored amounts.</p>
 *
 * <p>🔴 <b>Prompt 01 creates no rows here</b> (D5-2). The revenue-history feed has no settlementType,
 * so it cannot know which payout will carry a line; the payment-history feed (prompt 02) is what
 * declares the payout units. Until 02 ships, this table is legitimately empty and the settlement
 * screen shows no payout — that is not a bug.</p>
 *
 * <p>🔴 The unique key includes {@code settlementDate}: even on a weekly/monthly cycle, small
 * ADDITIONAL/RESERVE payouts arrive inside the same recognition month, so month + type alone
 * collides (D5-3, confirmed by the user).</p>
 *
 * <p>⚠️ A payout with ZERO lines is a normal state (D5-4) — reserve releases, debt repayments and
 * advertising settlements carry no sale lines at all.</p>
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → these @Column definitions must match changeset 083.</p>
 */
@Entity
@Table(name = "settlement_payout",
        uniqueConstraints = @UniqueConstraint(name = "uq_settlement_payout",
                columnNames = {"marketplace_account_id", "revenue_recognition_month",
                        "settlement_type", "settlement_date"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SettlementPayout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Settlement arrives per vendorId, so the account is the owning axis (D3). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", length = 20)
    private SettlementType settlementType;

    /** yyyy-MM — the revenue-recognition month, which is the axis the payment-history API is keyed on. */
    @Column(name = "revenue_recognition_month", length = 7)
    private String revenueRecognitionMonth;

    /** Recognition-date span this payout covers. Nullable: the platform does not always state it. */
    @Column(name = "recognition_from")
    private LocalDate recognitionFrom;

    @Column(name = "recognition_to")
    private LocalDate recognitionTo;

    /** Scheduled payout date. Part of the unique key (D5-3). */
    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    /** Confirmed payout date. Null while the payout is still scheduled. */
    @Column(name = "final_settlement_date")
    private LocalDate finalSettlementDate;

    @Column(name = "total_sale", precision = 15, scale = 2)
    private BigDecimal totalSale;

    @Column(name = "service_fee", precision = 15, scale = 2)
    private BigDecimal serviceFee;

    /**
     * 🔴 The amount actually paid = the right-hand side of the reconciliation equation
     * {@code Σ lines + Σ adjustments == finalAmount} (D9). Displaying it without running that
     * comparison throws away the only check we have.
     */
    @Column(name = "final_amount", precision = 15, scale = 2)
    private BigDecimal finalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private SettlementPayoutStatus status;

    /** Filled by prompt 02. Every row starts at PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "recon_status", length = 20)
    private SettlementReconStatus reconStatus;
}
