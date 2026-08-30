package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * One entry in an option's quantity vector: (option, product, quantity) (FEATURE_2608_06 / 3b-1).
 *
 * <p>The set of products across an option's items must equal the master's component set, and each
 * quantity must be ≥ 1 (enforced in the service). No {@code @TenantId} — isolation flows through the
 * parent {@link MasterProductOption} → {@link MasterProduct}. No {@code BaseEntity} — a pure vector row.</p>
 */
@Entity
@Table(name = "master_product_option_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mpoi_option_product", columnNames = {"master_product_option_id", "product_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProductOptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_option_id", nullable = false)
    private MasterProductOption option;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;
}
