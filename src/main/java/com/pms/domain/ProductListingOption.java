package com.pms.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * ProductListingOption entity representing an option variant of a platform listing.
 *
 * Business Logic:
 * - optionName: Option label (e.g., "Size M", "Color Red")
 * - sellingPrice: Selling price for this option (decimal, 2 decimal places)
 * - platformOptionId: Platform-specific option ID (e.g., Coupang option ID)
 * - productListing: Reference to the parent ProductListing
 *
 * Relationships:
 * - N ProductListingOptions : 1 ProductListing
 * - 1 ProductListingOption : N ProductListingProducts (via ProductListingProduct.productListingOption)
 *
 * Business Rules:
 * - This is the actual business unit for order/shipment processing
 * - Each option can be composed of multiple products (bundle support)
 * - sellingPrice is used as the base for margin calculation:
 *   Margin = sellingPrice - (product costs × qty) - commission - delivery - package
 *
 * <p>Audit (104 Step 1): extends {@link BaseEntity} — same reasoning as {@link ProductListing}; here it also
 * dates the approval data ({@code approval_status}/{@code platform_option_id}) written by {@code fetchStatus}.</p>
 *
 * @see com.pms.domain.ProductListing for the parent listing
 * @see com.pms.domain.ProductListingProduct for product composition
 */
@Entity
@Table(name = "product_listing_option")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Product listing option (SKU variant with selling price)")
public class ProductListingOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Product listing option ID", example = "1")
    private Long id;

    /**
     * Reference to parent ProductListing.
     * Lazy-loaded. Required field.
     * Cascade not needed - managed via service layer.
     *
     * @see com.pms.domain.ProductListing
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    @Schema(description = "Parent product listing")
    private ProductListing productListing;

    /**
     * Option name/label (max 255 chars).
     * Examples: "Size M", "Color Red", "Bundle Pack"
     * Required field.
     */
    @Column(length = 255, nullable = false, name = "option_name")
    @Schema(description = "Option name/label", example = "Size M")
    private String optionName;

    /**
     * Selling price for this option.
     * Precision: 10 digits, 2 decimal places (e.g., 12345.67).
     * This is the base price for margin calculation.
     * Required field.
     */
    @Column(nullable = false, precision = 10, scale = 2, name = "selling_price")
    @Schema(description = "Selling price (base for margin calculation)", example = "12999.99")
    private BigDecimal sellingPrice;

    /**
     * Display "original" (strike-through) price for Coupang register (73). Reverse-calculated from
     * {@code sellingPrice} and the seller×platform display discount rate: {@code sellingPrice / (1 − rate)}.
     * Nullable (rate=0 → equals sellingPrice; register falls back to sellingPrice when null).
     */
    @Column(precision = 10, scale = 2, name = "original_price")
    @Schema(description = "Display original (strike-through) price", example = "16249.99")
    private BigDecimal originalPrice;

    /**
     * Platform-specific option ID (e.g., Coupang option ID).
     * Max 255 chars. Nullable (not all platforms provide this).
     *
     * Examples:
     * - Coupang: "1234567890"
     * - NAVER: "opt_12345"
     */
    @Column(length = 255, nullable = true, name = "platform_option_id")
    @Schema(description = "Platform option ID", example = "opt_12345")
    private String platformOptionId;

    /**
     * Approval state on the market (FEATURE_2608_06 / 3c) — the source of truth for option approval.
     * New DRAFT options default to {@link OptionApprovalStatus#NOT_APPROVED}; pre-existing live options are
     * backfilled to {@code APPROVED} (changeset 013 defaultValue). {@code fetchStatus} flips matched options
     * to {@code APPROVED} after the market approves them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20, nullable = false)
    @Builder.Default
    @Schema(description = "Option approval state", example = "NOT_APPROVED")
    private OptionApprovalStatus approvalStatus = OptionApprovalStatus.NOT_APPROVED;

    /**
     * Coupang option-update id (sellerProductItemId), max 255 chars. Nullable — filled by {@code fetchStatus}
     * after approval (alongside {@link #platformOptionId}=vendorItemId, both approval-result data).
     * Distinct from {@link #platformOptionId} which is the vendorItemId used for order mapping (unchanged).
     */
    @Column(length = 255, nullable = true, name = "seller_product_item_id")
    @Schema(description = "Coupang seller product item id (for option updates)", example = "555666")
    private String sellerProductItemId;

    /**
     * Per-channel active flag (FEATURE_2608_06 / 42). The master is the single option universe, but a channel
     * (market) may carry a different subset of options: a channel cell copies <em>all</em> master options
     * (backward-compatible) and then toggles this flag per channel. {@code active=false} only excludes the option
     * from the market register/update payload — the row is <b>kept</b> (re-activation + order mapping preserved).
     *
     * <p>Two default roles (not duplicated): entity {@code @Builder.Default = true} = the create path
     * (channel-add copy) default; changeset 028 {@code defaultValueBoolean:true} backfills pre-existing live rows.
     * ⚠️ 006 BIT trap: boolean needs an explicit MySQL physical type (BIT(1)) — see changeset 028.</p>
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    @Schema(description = "Per-channel active flag (excluded from market payload when false)", example = "true")
    private Boolean active = true;

    /**
     * Per-channel stock override (FEATURE_2608_06 / 102). {@code null} = inherit the master option's
     * {@code stockQuantity}; if that is null too the payload falls back to 9999
     * ({@code ListingStockPolicy.DEFAULT_STOCK_QUANTITY}). Resolution = channel ?? master ?? 9999.
     *
     * <p>⚠️ D5: the override may not exceed the master option's value (ceiling = master ?? 9999) — the write
     * path rejects a larger value, and lowering the master clamps the channels that are above it. Clamping
     * never auto-pushes to the market ([수정 요청] does that).</p>
     */
    @Column(name = "stock_quantity")
    @Schema(description = "Per-channel stock override; null = inherit the master option's stock", example = "30")
    private Integer stockQuantity;

    /**
     * Origin of {@link #sellingPrice} (FEATURE_2609_19 / D1). {@code AUTO} = the margin reverse-calc value
     * (a cell [재생성] recomputes it); {@code MANUAL_OVERRIDE} = a price the user set for this channel only,
     * which a regeneration must leave alone (D2 — the same rule the detail HTML override already follows).
     *
     * <p>⚠️ {@link #sellingPrice} is ALWAYS the effective price whatever this says — never write read code
     * that reinterprets the price based on this field. It exists so the regeneration knows what to skip and
     * so the matrix can mark the cell as manually priced.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    @Builder.Default
    @Schema(description = "Origin of the selling price (AUTO = calculated, MANUAL_OVERRIDE = user-set)",
            example = "AUTO")
    private GeneratedContentSource priceSource = GeneratedContentSource.AUTO;

    /**
     * True = this option physically exists on the marketplace: Coupang issued a vendorItemId, or it was
     * approved at some point. Such an option cannot be removed there (approved options are not deletable),
     * so 87 forbids unchecking it and 88 locks the checkbox.
     *
     * <p>⚠️ Deliberately does NOT include {@code active}. 84's lock adds that third term on top of this one
     * (see {@code MasterProductServiceImpl#isOnMarket}); 87 must not, or every active option of a pushed cell
     * would be locked and "turn on, then undo before re-registering" would be impossible.</p>
     */
    public boolean isMarketRegistered() {
        return platformOptionId != null || approvalStatus == OptionApprovalStatus.APPROVED;
    }
}
