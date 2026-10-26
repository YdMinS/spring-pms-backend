package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.TenantId;

/**
 * The pool photo one stage's single-photo thumbnail uses (FEATURE_2610_32 D2 · D5 · D7). One row per stage of a
 * master, keyed exactly like {@link ThumbnailCollage}:
 *
 * <table>
 *   <tr><th>stage</th><th>{@code scope_key}</th><th>seller</th><th>productListing</th></tr>
 *   <tr><td>master</td><td>{@code MASTER}</td><td>null</td><td>null</td></tr>
 *   <tr><td>seller</td><td>{@code SELLER:{sellerId}}</td><td>set</td><td>null</td></tr>
 *   <tr><td>listing</td><td>{@code LISTING:{listingId}}</td><td>null</td><td>set</td></tr>
 * </table>
 *
 * <p>{@code image} is always a photo of the same master's pool (D7 — checked by the service). Deleting the master,
 * seller, listing or that pool photo deletes the row (ON DELETE CASCADE, changeset 118 · D10), so the stage falls
 * back to the upper stage. Keys are built by {@code com.pms.service.collage.CollageScopes}.</p>
 *
 * <p>⚠️ Immutable (no {@code @Setter}) — a new choice rebuilds via {@code toBuilder}.</p>
 */
@Entity
@Table(name = "thumbnail_photo",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_thumbnail_photo_scope", columnNames = {"master_product_id", "scope_key"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ThumbnailPhoto extends BaseEntity {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_image_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MasterProductImage image;
}
