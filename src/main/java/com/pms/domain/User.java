package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "member",
        uniqueConstraints = @UniqueConstraint(name = "uq_member_tenant_email",
                columnNames = {"tenant_id", "email"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). User is INTENTIONALLY excluded from @TenantId:
    // login resolves by email BEFORE the token is parsed, so TenantContext is empty at
    // authentication time — a tenant filter here would make findByEmail return 0 rows.
    // Tenant is carried via the JWT claim instead; keep the `= 1L` default since Hibernate
    // does not auto-set this column. email unique is tenant-scoped (uq_member_tenant_email).
    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    // email unique is now tenant-scoped (uq_member_tenant_email); same email allowed across tenants.
    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
}
