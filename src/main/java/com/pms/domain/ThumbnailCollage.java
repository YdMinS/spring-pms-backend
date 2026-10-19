package com.pms.domain;

import com.pms.domain.converter.CollageLayoutConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.TenantId;

/**
 * One stored collage stage (FEATURE_2610_21 D9 · D38 · D42). One row per stage of a master:
 *
 * <table>
 *   <tr><th>stage</th><th>{@code scope_key}</th><th>seller</th><th>productListing</th></tr>
 *   <tr><td>master default</td><td>{@code MASTER}</td><td>null</td><td>null</td></tr>
 *   <tr><td>seller</td><td>{@code SELLER:{sellerId}}</td><td>set</td><td>null</td></tr>
 *   <tr><td>listing</td><td>{@code LISTING:{listingId}}</td><td>null</td><td>set</td></tr>
 * </table>
 *
 * <p>{@code scope_key} carries the uniqueness ({@code uq_thumbnail_collage_scope(master_product_id, scope_key)}):
 * a unique key over the nullable seller/listing columns would not stop duplicates in MySQL. Keys are built by
 * {@code com.pms.service.collage.CollageScopes}.</p>
 *
 * <p>{@code enabled = false} = "collage switched off at this stage" (D42) — its {@code layout} is null. Deleting
 * the master, seller or listing deletes the row (ON DELETE CASCADE, changeset 112).</p>
 *
 * <p>⚠️ Immutable (no {@code @Setter}) — updates rebuild via {@code toBuilder}.</p>
 */
@Entity
@Table(name = "thumbnail_collage",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_thumbnail_collage_scope", columnNames = {"master_product_id", "scope_key"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ThumbnailCollage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Auto-set/filtered by Hibernate {@code @TenantId} — never set manually in the builder. */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MasterProduct masterProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Seller seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProductListing productListing;

    @Column(name = "scope_key", nullable = false, length = 40)
    private String scopeKey;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Convert(converter = CollageLayoutConverter.class)
    @Column(name = "layout", columnDefinition = "TEXT")
    private CollageLayout layout;
}
