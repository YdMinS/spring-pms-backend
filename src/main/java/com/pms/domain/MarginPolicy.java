package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * Margin preset per (seller, platform) — FEATURE_2608_06 / 3a.
 *
 * <p>A tenant-owned net-margin rate keyed by seller + platform. 3a keeps it a plain CRUD entity with a
 * uniqueness guard on (tenant, seller, platform). isDefault / fallback / rounding are deferred to 3b.</p>
 *
 * <p>{@code marginRate} is a net-profit ratio in [0.0, 0.9999], e.g. {@code 0.1500} = 15%.</p>
 *
 * <p>Tenant isolation via {@code @TenantId}: query-based SELECTs are auto-filtered and INSERTs
 * auto-stamped — do NOT add manual tenant conditions.</p>
 */
@Entity
@Table(name = "margin_policy",
        uniqueConstraints = @UniqueConstraint(name = "uq_margin_policy_seller_platform",
                columnNames = {"tenant_id", "seller_id", "platform"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MarginPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 009). Auto-set on INSERT, auto-filter on query SELECTs.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Column(nullable = false, length = 50)
    private String platform;                 // "COUPANG", "NAVER", ...

    // Net-profit ratio, e.g. 0.1500 = 15%. DECIMAL(5,4) → [0.0000, 0.9999].
    @Column(name = "margin_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal marginRate;

    // Display discount rate for Coupang originalPrice reverse-calc (73): originalPrice = salePrice / (1 − rate).
    // Nullable = treat as 0 (no discount shown → originalPrice == salePrice). Clamped to [0, 0.5] at calc time.
    @Column(name = "display_discount_rate", precision = 5, scale = 4)
    private BigDecimal displayDiscountRate;
}
