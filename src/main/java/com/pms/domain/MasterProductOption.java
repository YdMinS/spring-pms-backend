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

    /**
     * Per-option delivery override (FEATURE_2608_06 / 13). Nullable — null means "use the master default"
     * ({@link MasterProduct#getDefaultDelivery()}). Resolution = this override ?? master default.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = true)
    private CarrierRate delivery;

    /**
     * Per-option box override (FEATURE_2608_06 / 13). Nullable — null means "use the master default"
     * ({@link MasterProduct#getDefaultPackage()}). Getter is {@code getPackage_()} ({@code package} is reserved).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = true)
    private Package package_;
}
