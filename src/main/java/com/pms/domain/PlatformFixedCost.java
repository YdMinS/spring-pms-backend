package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * 플랫폼 단위 고정비 <b>카탈로그</b> 한 줄 (FEATURE_2609_33 / PLAN 2609_33 D1 · D2-1 · D3).
 *
 * <p>예: 쿠팡 `판매자서비스이용료` 55,000원 / 임계 1,000,000원.
 *
 * <p>🔴 <b>금액·기본 임계의 소유자는 여기 하나</b>다. {@link MarketplaceAccountFixedCost} 는 이 항목을
 * <b>연결만</b> 하고 금액을 복사하지 않는다 — 요율이 바뀔 때 채널마다 고치면 하나를 빠뜨리는 순간
 * 순이익이 조용히 틀린다.
 *
 * <p>🔴 {@code amount} 는 <b>부가세 포함</b> 값이다(D3). 쿠팡 공식 금액이 이미 VAT 포함 55,000원이라
 * 여기에 세금을 또 곱하면 매달 5,000원이 과대 계상된다.
 *
 * <p>⚠️ {@code active = false} 는 <b>과거 달도 세지 않는다</b> — 항목을 끄면 그 항목은 계산에서 완전히
 * 빠진다. 기간을 끊고 싶은 것이라면 {@link MarketplaceAccountFixedCost#getAppliedTo()} 를 쓴다(D5).
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → 아래 @Column 정의는 changeset 086 과 일치해야 한다.
 * {@code active} 는 MySQL 에서 BIT(1) 로 재타이핑된다(086 두 번째 changeSet).
 */
@Entity
@Table(name = "platform_fixed_cost")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformFixedCost extends BaseEntity {

    /** 카탈로그 기본 임계 — 쿠팡 일반 카테고리 기준(D2-1). 가전·디지털(5,000,000)은 채널에서 덮는다. */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("1000000");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 50)
    private Platform platform;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 월 정액. 🔴 VAT 포함 값 그대로다(D3) — 주기 컬럼도 일할 계산도 없다. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** 부과 임계. 그 달 채널 매출(할인 후)이 이 값 <b>이상</b>이면 부과된다. */
    @Column(name = "threshold_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
