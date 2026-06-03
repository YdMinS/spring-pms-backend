package com.pms.dto.response;

import com.pms.domain.ProductListing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product listing response")
public class ProductListingResponse {

    @Schema(description = "Product listing ID", example = "1")
    private Long id;

    @Schema(description = "Seller ID", example = "1")
    private Long sellerId;

    @Schema(description = "Seller name", example = "John's Shop")
    private String sellerName;

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Platform product ID", example = "12345678")
    private String platformProductId;

    @Schema(description = "Category ID", example = "1")
    private Long categoryId;

    @Schema(description = "Category name", example = "Electronics")
    private String categoryName;

    @Schema(description = "Delivery (CarrierRate) ID", example = "1")
    private Long deliveryId;

    @Schema(description = "Carrier name", example = "CJ Logistics")
    private String carrierName;

    @Schema(description = "Package ID", example = "1")
    private Long packageId;

    @Schema(description = "Package type", example = "Box_Standard")
    private String packageType;

    public static ProductListingResponse of(ProductListing listing) {
        return ProductListingResponse.builder()
                .id(listing.getId())
                .sellerId(listing.getSeller() != null ? listing.getSeller().getId() : null)
                .sellerName(listing.getSeller() != null ? listing.getSeller().getSellerName() : null)
                .platform(listing.getPlatform())
                .platformProductId(listing.getPlatformProductId())
                .categoryId(listing.getCategory() != null ? listing.getCategory().getId() : null)
                .categoryName(listing.getCategory() != null ? listing.getCategory().getName() : null)
                .deliveryId(listing.getDelivery() != null ? listing.getDelivery().getId() : null)
                .carrierName(listing.getDelivery() != null ? listing.getDelivery().getCarrier() : null)
                .packageId(listing.getPackage_() != null ? listing.getPackage_().getId() : null)
                .packageType(listing.getPackage_() != null ? listing.getPackage_().getType() : null)
                .build();
    }
}
