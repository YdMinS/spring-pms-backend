package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * One marketplace photo of a listing, copied into our storage (FEATURE_2611_11 / D8 · D21 · D36 · D37).
 *
 * <p>Written by an import and by [「마켓 기준으로 맞추기」] only — the whole set of a listing is replaced at once.
 * {@code productListingOptionId} = the listing option the photo belongs to (a Coupang {@code items[]} photo, D21);
 * {@code null} = a product-level photo (11st · ESM). {@code representative} = the market's main photo of that
 * option (Coupang {@code REPRESENTATION}) or of the product (11st {@code BasicImage} · ESM {@code basicImgURL}).
 * {@code marketImageType} = the market's own type text (Coupang {@code imageType}) sent back unchanged; {@code null}
 * on markets without one. {@code sortOrder} = the order the market returned (D8).</p>
 *
 * <p>{@code @TenantId} (backend rule B6) — the rows are always read through a listing the caller already resolved.
 * {@code productListingOptionId} is a plain id (no FK): the photos of an option that no longer exists are simply not
 * sent. A part row of a listing → no {@code BaseEntity} (audit-column rule, backend CLAUDE.md §10).</p>
 */
@Entity
@Table(name = "listing_market_image")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ListingMarketImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Hibernate auto-sets this on INSERT and auto-filters SELECTs — do NOT set it in the builder.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    private ProductListing productListing;

    @Column(name = "product_listing_option_id")
    private Long productListingOptionId;

    @Column(name = "representative", nullable = false)
    private Boolean representative;

    @Column(name = "market_image_type", length = 30)
    private String marketImageType;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;
}
