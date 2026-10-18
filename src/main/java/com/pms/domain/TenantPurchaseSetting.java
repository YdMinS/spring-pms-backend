package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * Tenant purchase settings (FEATURE_2610_09 / D65 · D69 · D72). One row per tenant.
 *
 * <p>No row = stock link off ({@code PurchaseSettingServiceImpl.DEFAULT_STOCK_LINKED}, D69).</p>
 */
@Entity
@Table(name = "tenant_purchase_setting",
        uniqueConstraints = @UniqueConstraint(name = "uq_tenant_purchase_setting_tenant", columnNames = {"tenant_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TenantPurchaseSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** true = a purchase is also added to stock (D65). */
    @Column(name = "stock_linked", nullable = false)
    private Boolean stockLinked;
}
