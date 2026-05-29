package com.pms.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

/**
 * ProductListingProduct entity representing a product composition within a listing option.
 *
 * Business Logic:
 * - productListingOption: Parent option that this product belongs to
 * - product: Reference to the actual Product entity
 * - quantity: Quantity of this product in the option (for bundle support)
 *
 * Relationships:
 * - N ProductListingProducts : 1 ProductListingOption
 * - M ProductListingProducts : M Products (via foreign key)
 *
 * Business Rules:
 * - One option can consist of multiple products (bundle/kit scenario)
 * - Each composition includes a quantity value
 * - Product cost = product.price × quantity
 * - Used in margin calculation:
 *   Total product cost = sum of (product.price × quantity) for all products in option
 *
 * Examples:
 * - Option "3+1 Bundle": Product A (qty 3) + Product B (qty 1)
 * - Option "Single Product": Product C (qty 1)
 *
 * @see com.pms.domain.ProductListingOption for the parent option
 * @see com.pms.domain.Product for the referenced product
 */
@Entity
@Table(name = "product_listing_product")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Product composition within a listing option (for bundle support)")
public class ProductListingProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Product listing product ID", example = "1")
    private Long id;

    /**
     * Reference to parent ProductListingOption.
     * Lazy-loaded. Required field.
     * This option contains this product composition.
     *
     * @see com.pms.domain.ProductListingOption
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_option_id", nullable = false)
    @Schema(description = "Parent product listing option")
    private ProductListingOption productListingOption;

    /**
     * Reference to the Product entity.
     * Lazy-loaded. Required field.
     * FK to products table.
     *
     * @see com.pms.domain.Product
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @Schema(description = "Product in this composition")
    private Product product;

    /**
     * Quantity of this product in the parent option.
     * Must be >= 1. Not null.
     * Examples:
     * - Bundle "3+1": 3 for Product A, 1 for Product B
     * - Single product option: 1
     */
    @Column(nullable = false)
    @Schema(description = "Quantity of product in this composition", example = "3")
    private Integer quantity;
}
