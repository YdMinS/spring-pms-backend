package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * "이 채널이 저 고정비 항목을 부담한다" 는 연결 (FEATURE_2609_33 / PLAN 2609_33 D1 · D2 · D2-1 · D5).
 *
 * <p>🔴 <b>금액이 여기 없다</b>(D1). 금액과 기본 임계는 {@link PlatformFixedCost} 에만 있고 여기에는
 * "덮어쓸 것"(모드·임계·적용 구간)만 있다.
 *
 * <p>적용 구간({@code appliedFrom} ~ {@code appliedTo}, 둘 다 {@code YYYY-MM})은 계약 전/후 달을 빼기
 * 위한 것이다(D5) — 없으면 계약 전 과거 달 순이익이 실제보다 낮게 나온다. 둘 다 null = 전 기간.
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → 아래 @Column 정의는 changeset 086 과 일치해야 한다.
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

    /** 실효 임계 — 채널 override 가 있으면 그것, 없으면 카탈로그 기본값(D2-1). */
    public BigDecimal effectiveThreshold() {
        if (thresholdOverride != null) {
            return thresholdOverride;
        }
        return platformFixedCost == null ? null : platformFixedCost.getThresholdAmount();
    }
}
