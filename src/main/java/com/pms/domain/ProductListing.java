package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import com.pms.domain.converter.StringListConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.List;
import java.util.Map;

/**
 * ProductListing entity representing a product registered on a platform (e.g., Coupang).
 *
 * Business Logic:
 * - platform: Platform identifier (e.g., "COUPANG", "AMAZON")
 * - platformProductId: Platform's product ID (업체상품 ID on Coupang)
 * - category: Category for commission rate lookup
 * - delivery: Default carrier rate for this listing
 * - package: Default package/box cost for this listing
 *
 * Relationships:
 * - 1 ProductListing : N ProductListingOptions (via ProductListingOption.productListing)
 * - Each option has its own sellingPrice and platformOptionId
 *
 * Note: This is the highest level in the listing hierarchy.
 * Options and products are managed separately.
 *
 * @see com.pms.domain.ProductListingOption for options under this listing
 * @see com.pms.domain.Category for commission rate rules
 * @see com.pms.domain.CarrierRate for default delivery cost
 * @see com.pms.domain.Package for default box cost
 */
@Entity
@Table(name = "product_listing")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Product listing on a platform (e.g., Coupang product)")
public class ProductListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Product listing ID", example = "1")
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * Platform identifier (max 50 chars).
     * Examples: "COUPANG", "AMAZON", "NAVER"
     * Required field.
     */
    @Column(length = 50, nullable = false)
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    /**
     * Platform-specific product ID (업체상품 ID on Coupang).
     * Unique per platform, max 255 chars.
     *
     * <p>Nullable since FEATURE_2608_06 / 3b' (changeset 012): a channel-add DRAFT cell has no market id
     * until it is pushed in 3c. Live listings still carry it.</p>
     */
    @Column(length = 255, nullable = true, name = "platform_product_id")
    @Schema(description = "Platform product ID (업체상품 ID)", example = "12345678")
    private String platformProductId;

    /**
     * Lifecycle status (FEATURE_2608_06 / 3b'). Channel-add creates {@link ListingStatus#DRAFT} cells;
     * promotion is driven by 3c. Defaults to {@link ListingStatus#SELLING} so the legacy CRUD create path
     * (live listings) and pre-existing rows (backfilled by changeset 012) stay consistent — unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    @Schema(description = "Listing lifecycle status", example = "DRAFT")
    private ListingStatus status = ListingStatus.SELLING;

    /**
     * Product listing name (max 255 chars).
     * Display name for the listing shown in list views.
     * Examples: "Galaxy S21 Bundle", "iPhone 13 Pro 3종"
     * Required field.
     */
    @Column(length = 255, nullable = false)
    @Schema(description = "Product listing name", example = "Galaxy S21 Bundle")
    private String name;

    /**
     * Seller who registered this product listing.
     * Required field - every listing must belong to a seller.
     *
     * @see com.pms.domain.Seller
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    @Schema(description = "Seller who registered this listing")
    private Seller seller;

    /**
     * Category for commission rate lookup.
     * Lazy-loaded. Can be null if platform has default commission rate.
     *
     * @see com.pms.domain.Category
     * @see com.pms.domain.CommissionRate
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = true)
    @Schema(description = "Category for commission rate lookup")
    private Category category;

    /**
     * Default carrier/delivery rate for this listing.
     * Lazy-loaded. Can be null (use default carrier rate).
     *
     * @see com.pms.domain.CarrierRate
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = true)
    @Schema(description = "Default carrier rate for delivery cost")
    private CarrierRate delivery;

    /**
     * Default package/box cost for this listing.
     * Lazy-loaded. Can be null (use default package).
     *
     * @see com.pms.domain.Package
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = true)
    @Schema(description = "Default package for box cost")
    private Package package_;

    /**
     * Master product this channel cell belongs to (Design 2, FEATURE_2608_06 / 3a).
     *
     * <p>Additive grouping link only — everything else on this entity is unchanged. Transitional
     * nullable (filled by the shared-id backfill in changeset 009); channel-add (3b') will drive new
     * cells through the master.</p>
     *
     * @see com.pms.domain.MasterProduct
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = true)
    @Schema(description = "Master product grouping this listing (Design 2)")
    private MasterProduct masterProduct;

    /**
     * Market-sync dirty marker (FEATURE_2608_06 / 3d, Design 2). {@code true} = "local assets were regenerated
     * (layer A: {@code MasterPropagationService.propagate}) and this cell has NOT yet been pushed to the market"
     * — i.e. pending market reflection. Only <b>on-market</b> cells ({@code platformProductId != null}) are
     * marked true during propagation; DRAFT cells (not yet pushed) are never marked. Layer B
     * ({@code ListingPropagationService.pushSync}) clears it back to {@code false} after a successful push.
     *
     * <p>Additive column only — pre-existing live rows are backfilled to {@code false} (not just regenerated,
     * so not pending). See changeset 014.</p>
     */
    @Column(name = "needs_market_sync", nullable = false)
    @Builder.Default
    @Schema(description = "Regenerated locally but not yet pushed to the market (pending sync)", example = "false")
    private boolean needsMarketSync = false;

    /**
     * Per-channel text field-value overrides (FEATURE_2608_06 / 12, Design 2). Reuses the master's
     * {@link MapStringConverter} (JSON TEXT). During asset (re)generation these are layered <b>on top of</b>
     * the master field values per key, so the render priority is
     * {@code listing.fieldValues (non-blank) > master.fieldValues (non-blank) > reserved-key product value}
     * (blank keys still fall back to the template defaultValue in the renderer — unchanged).
     *
     * <p>⚠️ {@code null} = no override (NOT a {@code @Builder.Default} empty map) — the legacy CRUD/create
     * path and pre-existing rows leave it unset (nullable, no live backfill). 3d propagation reuses
     * {@code regenerateAssets}, which reads this column, so channel overrides are preserved on re-generation
     * (propagation never touches this column). See changeset 017.</p>
     */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "field_values", columnDefinition = "TEXT")
    @Schema(description = "Per-channel text field-value overrides")
    private Map<String, String> fieldValues;

    /**
     * Per-channel raw tags (33): this cell's own tags, ordered and deduped on save (JSON TEXT, H2/MySQL
     * portable). At push time these come <b>first</b>, then the master's {@code tags} are appended (skipping
     * duplicates) and truncated to the platform cap — see {@code TagMergeService.resolveTags}.
     *
     * <p>⚠️ {@code null} = "no channel tags" (NOT a {@code @Builder.Default} empty list): the legacy CRUD/create
     * path and pre-existing rows leave it unset (nullable, no live backfill). See changeset 023.</p>
     */
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Per-channel raw tags")
    private List<String> tags;
}
