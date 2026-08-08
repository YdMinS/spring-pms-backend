package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "seller",
        uniqueConstraints = @UniqueConstraint(name = "uq_seller_tenant_biz",
                columnNames = {"tenant_id", "business_registration"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Seller extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). TODO(02): remove `= 1L` default when @TenantId resolver is added.
    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(nullable = false, length = 255)
    private String sellerName;

    // business_registration unique is now tenant-scoped (uq_seller_tenant_biz).
    @Column(nullable = false, length = 50)
    private String businessRegistration;
}
