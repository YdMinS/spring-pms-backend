package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Platform category for a master product (FEATURE_2608_06 / 13).
 *
 * <p>Category = master × platform: the same master can sit under a different category on Coupang vs Naver,
 * but within one platform the category is the same regardless of seller. So there is at most one row per
 * (master, platform) — enforced by {@code uq_mpc_master_platform}.</p>
 *
 * <p>No {@code @TenantId} and no {@link BaseEntity} — a pure config child; isolation flows through the parent
 * {@link MasterProduct} (the repository exposes master-scoped finders only). Immutable ({@code toBuilder},
 * no {@code @Setter}).</p>
 */
@Entity
@Table(name = "master_product_category",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mpcat_master_platform", columnNames = {"master_product_id", "platform"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    private MasterProduct masterProduct;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}
