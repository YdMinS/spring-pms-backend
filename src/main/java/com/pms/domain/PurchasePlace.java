package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.List;

/**
 * A place a product can be bought at — the tenant-wide purchase place list (FEATURE_2609_76 / D2).
 *
 * <p>Products point at this row by id through {@link ProductPurchasePlace} (D3 · D20), never by name, so a
 * rename here shows up on every product at once. Managed on the web 「설정 &gt; 구매처 관리」 screen by ADMIN
 * only (D14); every signed-in user may read the list.</p>
 *
 * <p>⚠️ {@code name} is unique per tenant after trimming (D15, {@code uq_purchaseplace_tenant_name}).
 * {@code sortOrder} is {@code max + 1} on create and never changes — there is no reorder endpoint.</p>
 *
 * <p>Tenant-isolated via Hibernate {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — never set
 * {@code tenantId} in the builder). Immutable (no {@code @Setter}) — a rename rebuilds via {@code toBuilder}.</p>
 */
@Entity
@Table(name = "purchase_place",
        uniqueConstraints = @UniqueConstraint(name = "uq_purchaseplace_tenant_name",
                columnNames = {"tenant_id", "name"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PurchasePlace extends BaseEntity {

    /**
     * The three places a tenant starts with (D2), in this order. Seeded by changeset 102 for every tenant that
     * existed at migration time, and by {@code PurchasePlaceServiceImpl.list} for a tenant whose list is empty
     * (D19). 🔴 Changeset 102 spells the same three names — change both or neither.
     */
    public static final List<String> DEFAULT_NAMES = List.of("이마트", "코스트코", "노브랜드");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Auto-set/filtered by Hibernate {@code @TenantId} — never set manually in the builder. */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Display name — the only mutable field. Unique per tenant (D15). */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Position in the list (0-based, creation order). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
