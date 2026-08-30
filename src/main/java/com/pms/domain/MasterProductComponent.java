package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Membership link "this master is composed of this product" (FEATURE_2608_06 / 3b-1).
 *
 * <p>Quantity-less: it records only which products make up the master (the per-option quantity vector
 * lives in {@link MasterProductOptionItem}). No {@code @TenantId} — isolation flows through the parent
 * {@link MasterProduct} (repositories expose master-scoped finders only). No {@code BaseEntity} — a pure
 * join row with no audit need; updates are done by delete + re-insert.</p>
 */
@Entity
@Table(name = "master_product_component",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mpc_master_product", columnNames = {"master_product_id", "product_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProductComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    private MasterProduct masterProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
}
