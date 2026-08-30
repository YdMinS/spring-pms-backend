package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * Marketplace category tree node that OWNS the mall code + commission rate (FEATURE_2608_06 / 52).
 *
 * <p>The oclyx {@link Category} is only a classification/selection axis (no mall code, no commission). The
 * actual per-platform marketplace code and its commission live here: a {@code PlatformCategory} is one node
 * in a platform's own category tree (self-referencing {@code parent}), and commission resolution is
 * {@code master → oclyx Category → CategoryMapping(platform) → PlatformCategory.commissionRate} = O(1) (no
 * runtime tree climbing — the mapped node's value is used directly).</p>
 *
 * <p>Tenant isolation: {@code @TenantId} auto-filters query-based SELECTs and auto-stamps INSERTs (per-tenant
 * marketplace category tree, consistent with the multi-tenant model). @Setter is forbidden — use
 * {@code toBuilder}.</p>
 *
 * <p>⚠️ Only <b>leaf</b> nodes carry a {@code code} (the mall code) and a commission; intermediate nodes are
 * path segments created by the 53 import with {@code code = null} and (optionally) {@code commissionRate =
 * null}. The unique index (platform, code) tolerates multiple NULL codes on both MySQL and H2 (MODE=MySQL),
 * so intermediate nodes coexist fine (no partial-unique / app-level fallback needed).</p>
 *
 * @see CategoryMapping for the standard → platform-category FK link
 * @see com.pms.repository.PlatformCategoryRepository
 */
@Entity
@Table(name = "platform_category",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_platcat_platform_code", columnNames = {"platform", "code"}),
        indexes = {
                @Index(name = "idx_platcat_platform_parent_name",
                        columnList = "platform, parent_id, name"),
                @Index(name = "idx_platcat_parent", columnList = "parent_id")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets on INSERT and auto-filters query-based SELECTs — do NOT add
    // manual tenant conditions.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Marketplace name (e.g. "COUPANG", "NAVER"). */
    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    /** Mall category code. Leaf = the marketplace code; intermediate node = {@code null} (path segment). */
    @Column(name = "code", length = 100)
    private String code;

    /** Node label (the category name segment). */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Parent node in the platform's own tree; {@code null} = root. Lazy to avoid N+1. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = true)
    private PlatformCategory parent;

    /**
     * Sales-agency commission as a fraction of price (e.g. {@code 0.10} = 10%), consistent with the margin
     * rate the price engine subtracts. Nullable; a node may carry commission even when it is not a leaf. At
     * runtime a mapped node with {@code null} commission means the category was not seeded properly (→ 400),
     * never a silent fallback.
     */
    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;
}
