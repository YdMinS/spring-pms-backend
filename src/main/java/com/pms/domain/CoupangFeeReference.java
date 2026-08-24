package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Coupang static commission fee reference (FEATURE_2608_06 / 46).
 *
 * <p>One flat row of the 2019 Coupang fee table: {@code (dae, jung, so, rate)} = major / middle / sub
 * category names + the base commission rate (decimal, e.g. {@code 0.108} = 10.8%). Rows where
 * {@code jung}/{@code so} are blank are the major/middle "기본 수수료" (default) rows, used by the
 * hierarchical fallback in {@link com.pms.service.CoupangFeeResolver}.</p>
 *
 * <p>Global reference data — NO {@link BaseEntity} and NO {@code @TenantId} (shared across all tenants,
 * seeded by {@link com.pms.service.CoupangFeeReferenceSeeder}). Immutable ({@code toBuilder}, no
 * {@code @Setter}). The 2019 table is only a <b>default</b>; per-category actual rates are user-editable
 * via {@link CommissionRate} (the prefill writes {@code isDefault=false} category rows there).</p>
 */
@Entity
@Table(name = "coupang_fee_reference")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CoupangFeeReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Major category (대분류), always present. */
    @Column(name = "dae", nullable = false, length = 100)
    private String dae;

    /** Middle category (중분류); blank for a major-default row. */
    @Column(name = "jung", length = 100)
    private String jung;

    /** Sub category (소분류); blank for a major/middle-default row. */
    @Column(name = "so", length = 100)
    private String so;

    /** Base commission rate as decimal (e.g. 0.1080 = 10.8%). */
    @Column(name = "rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;
}
