package com.pms.dto.response;

import com.pms.domain.ProductListing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

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

    @Schema(description = "Product listing name", example = "Galaxy S21 Bundle")
    private String name;

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

    /**
     * 이 셀이 속한 마스터 프로덕트 id — null = 마스터 미연결(legacy `판매상품 등록` 으로 만든 셀, 2609_22/04).
     * 목록 화면의 배지·[마스터 만들기] 버튼 분기가 이 필드를 본다.
     */
    @Schema(description = "Linked master product ID; null = not linked to any master", example = "88",
            nullable = true)
    private Long masterProductId;

    @Schema(description = "Product listing options")
    private List<ProductListingOptionResponse> options;

    public static ProductListingResponse of(ProductListing listing) {
        return ProductListingResponse.builder()
                .id(listing.getId())
                .sellerId(listing.getSeller() != null ? listing.getSeller().getId() : null)
                .sellerName(listing.getSeller() != null ? listing.getSeller().getSellerName() : null)
                .platform(listing.getPlatform().name())
                .platformProductId(listing.getPlatformProductId())
                .name(listing.getName())
                .categoryId(listing.getCategory() != null ? listing.getCategory().getId() : null)
                .categoryName(listing.getCategory() != null ? listing.getCategory().getName() : null)
                .deliveryId(listing.getDelivery() != null ? listing.getDelivery().getId() : null)
                .carrierName(listing.getDelivery() != null && listing.getDelivery().getCarrier() != null
                        ? listing.getDelivery().getCarrier().getName() : null)
                .packageId(listing.getPackage_() != null ? listing.getPackage_().getId() : null)
                .packageType(listing.getPackage_() != null ? listing.getPackage_().getType() : null)
                // LAZY proxy — reading the FK id alone does not trigger a load.
                .masterProductId(listing.getMasterProduct() != null ? listing.getMasterProduct().getId() : null)
                .build();
    }

    public static ProductListingResponse of(ProductListing listing, List<ProductListingOptionResponse> options) {
        return ProductListingResponse.builder()
                .id(listing.getId())
                .sellerId(listing.getSeller() != null ? listing.getSeller().getId() : null)
                .sellerName(listing.getSeller() != null ? listing.getSeller().getSellerName() : null)
                .platform(listing.getPlatform().name())
                .platformProductId(listing.getPlatformProductId())
                .name(listing.getName())
                .categoryId(listing.getCategory() != null ? listing.getCategory().getId() : null)
                .categoryName(listing.getCategory() != null ? listing.getCategory().getName() : null)
                .deliveryId(listing.getDelivery() != null ? listing.getDelivery().getId() : null)
                .carrierName(listing.getDelivery() != null && listing.getDelivery().getCarrier() != null
                        ? listing.getDelivery().getCarrier().getName() : null)
                .packageId(listing.getPackage_() != null ? listing.getPackage_().getId() : null)
                .packageType(listing.getPackage_() != null ? listing.getPackage_().getType() : null)
                // LAZY proxy — reading the FK id alone does not trigger a load.
                .masterProductId(listing.getMasterProduct() != null ? listing.getMasterProduct().getId() : null)
                .options(options)
                .build();
    }
}
