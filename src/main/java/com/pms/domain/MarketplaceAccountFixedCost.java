package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * The link "this channel bears that fixed-cost item" (FEATURE_2609_33 / PLAN 2609_33 D1 · D2 · D2-1 · D5).
 *
 * <p>🔴 <b>No amount here</b> (D1). The amount and the default threshold live only in {@link PlatformFixedCost}; this
 * row holds only "what to override" (mode · threshold · applied months) — plus, for an 11st server-fee link, the
 * first-month (M0) judgement ({@code firstMonth} · {@code firstMonthState} · {@code firstMonthCharged},
 * FEATURE_2610_20 / D45 · D46).
 *
 * <p>The applied months ({@code appliedFrom} ~ {@code appliedTo}, both {@code YYYY-MM}) cut off months before / after
 * the contract (D5) — without them, past months before the contract show a lower profit than they had. Both null =
 * every month.
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions below must match changesets 086 and 111-2.
 */
@Entity
@Table(name = "marketplace_account_fixed_cost")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MarketplaceAccountFixedCost extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "platform_fixed_cost_id", nullable = false)
    private PlatformFixedCost platformFixedCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_mode", nullable = false, length = 10)
    private FixedCostChargeMode chargeMode;

    /** null = 카탈로그의 {@code thresholdAmount} 를 쓴다(D2-1). */
    @Column(name = "threshold_override", precision = 15, scale = 2)
    private BigDecimal thresholdOverride;

    /** {@code YYYY-MM}. null = 전 기간(그 이전 달도 부과 대상). */
    @Column(name = "applied_from", length = 7)
    private String appliedFrom;

    /** {@code YYYY-MM}. null = 진행 중(종료월 없음). */
    @Column(name = "applied_to", length = 7)
    private String appliedTo;

    /**
     * 11st server fee only (FEATURE_2610_20 / D45 · D46): M0 = the month of the earliest purchase-confirmed date stored
     * when the judgement started, {@code YYYY-MM}. Written once, never changed. null = not judged yet / not 11st.
     */
    @Column(name = "first_month", length = 7)
    private String firstMonth;

    /** 11st server fee only: where the M0 judgement stands (D45). null = not judged yet / not 11st. */
    @Enumerated(EnumType.STRING)
    @Column(name = "first_month_state", length = 10)
    private FirstMonthState firstMonthState;

    /** 11st server fee only: the person's answer (charged in M0 or not). Set only with {@link FirstMonthState#ANSWERED}. */
    @Column(name = "first_month_charged")
    private Boolean firstMonthCharged;

    /** 실효 임계 — 채널 override 가 있으면 그것, 없으면 카탈로그 기본값(D2-1). */
    public BigDecimal effectiveThreshold() {
        if (thresholdOverride != null) {
            return thresholdOverride;
        }
        return platformFixedCost == null ? null : platformFixedCost.getThresholdAmount();
    }
}
