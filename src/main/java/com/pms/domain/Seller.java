package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

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

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 255)
    private String sellerName;

    // business_registration unique is now tenant-scoped (uq_seller_tenant_biz).
    @Column(nullable = false, length = 50)
    private String businessRegistration;
}
