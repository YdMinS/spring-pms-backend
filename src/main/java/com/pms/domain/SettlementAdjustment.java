package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * A payout-level amount that belongs to no sale line (PLAN 2609_30 D8 · D13).
 *
 * <p>Deductions, last week's carried debt and reserve releases are charged against the payout as a
 * whole. 🔴 Spreading them over the lines would fold advertising cost into product cost and
 * silently poison per-product profitability — that is the failure mode this table exists to prevent.</p>
 *
 * <p>⚠️ <b>Prompt 01 creates no rows here.</b> The payment-history feed (prompt 02) owns this table;
 * 01 only creates it so 02 lands as a pure additive change.</p>
 *
 * <p>⚠️ {@code note} is usually null: Coupang sends the amount without a reason, and prompt 02 labels
 * those "플랫폼 확인 필요" rather than inventing an explanation (D13).</p>
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → these @Column definitions must match changeset 083.</p>
 */
@Entity
@Table(name = "settlement_adjustment")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SettlementAdjustment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_payout_id", nullable = false)
    private SettlementPayout settlementPayout;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private SettlementAdjustmentType adjustmentType;

    /** Stored as the platform sent it (positive). The sign is implied by {@link #adjustmentType}. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", length = 500)
    private String note;
}
