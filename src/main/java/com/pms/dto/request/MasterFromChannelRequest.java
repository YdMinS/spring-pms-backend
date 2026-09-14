package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 마켓 상품으로 마스터 만들기 — 생성 요청(FEATURE_2609_45 / D3): <b>마켓에 없는 정보만</b> 받는다
 * (마스터 이름 · 구성상품 · 옵션별 수량 · 표준 카테고리).
 *
 * <p>⚠️ 가격·재고·옵션 식별자·옵션명·태그·상태는 여기서 받지 않는다 — 커밋 시 마켓을 한 번 더 조회해 서버가
 * 확정한다(미리보기~커밋 사이의 변경 반영 + 클라이언트 값 신뢰 금지).</p>
 *
 * <p>⚠️ 마스터 옵션명도 받지 않는다 — 마켓 {@code itemName} 을 그대로 쓴다(신규 마스터라 "기존 옵션에
 * 연결" 분기 자체가 없다).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create a master product (+ options + channel cell) from a marketplace product")
public class MasterFromChannelRequest {

    @NotNull(message = "Seller ID cannot be null")
    @Schema(description = "Seller ID (the account's seller)", example = "3")
    private Long sellerId;

    @NotBlank(message = "Platform cannot be blank")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "Platform product ID cannot be blank")
    @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "1234567")
    private String platformProductId;

    @NotBlank(message = "Master name cannot be blank")
    @Schema(description = "Name of the master product to create", example = "노브랜드 생수 2L")
    private String masterName;

    @NotNull(message = "Category ID cannot be null")
    @Schema(description = "Standard category id (D2: prefilled by the preview, or picked by the user)",
            example = "3")
    private Long categoryId;

    @NotEmpty(message = "Component product IDs cannot be empty")
    @Schema(description = "The master's component products (user picked)", example = "[11, 12]")
    private List<Long> componentProductIds;

    @NotEmpty(message = "Options cannot be empty")
    @Valid
    @Schema(description = "One entry per marketplace option; the set must equal the marketplace's")
    private List<OptionSpec> options;

    /** 마켓 옵션 하나에 대응하는 입력: 구성상품별 수량만(D6). */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Per-marketplace-option composition input")
    public static class OptionSpec {

        // 🔴 엔티티가 쓰는 중립 이름(ProductListingOption.platformOptionId)을 그대로 쓴다 — 형제 DTO
        //    (ListingImportRequest)의 vendorItemId 는 이어받을 관례가 아니라 정리 대상이다.
        @Schema(description = "Marketplace option id (Coupang vendorItemId); null = match by itemName",
                example = "8123")
        private String platformOptionId;

        @NotBlank(message = "Item name cannot be blank")
        @Schema(description = "Option name as shown on the marketplace", example = "6입")
        private String itemName;

        @NotEmpty(message = "Components cannot be empty")
        @Valid
        @Schema(description = "Quantity per component — every component must be present (D6)")
        private List<Component> components;
    }

    /** 구성 한 줄: 구성상품 하나의 수량(D6 = 전 구성품 고정, 수량만 입력). */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Quantity of one component in this option")
    public static class Component {

        @NotNull(message = "Product ID cannot be null")
        @Schema(description = "Product id (must be one of componentProductIds)", example = "11")
        private Long productId;

        @NotNull(message = "Quantity cannot be null")
        @Schema(description = "Quantity (>= 1)", example = "6")
        private Integer quantity;
    }
}
