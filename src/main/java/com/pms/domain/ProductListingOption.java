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
public class ProductListingOption {

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
}
