package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * An option (SKU variant) of a master product, e.g. "1세트" / "2세트" (FEATURE_2608_06 / 3b-1).
 *
 * <p>Each option carries a quantity vector over the master's component products via
 * {@link MasterProductOptionItem}. Its items must cover the full component set (see the service
 * validation). No {@code @TenantId} — isolation flows through the parent {@link MasterProduct}.</p>
 */
@Entity
@Table(name = "master_product_option")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProductOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    private MasterProduct masterProduct;

    @Column(name = "name", nullable = false, length = 255)
    private String name;
}
