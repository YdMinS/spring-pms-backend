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
 * 가져오기 커밋 요청(FEATURE_2609_22 / D9~D11): 미리보기에서 사용자가 채운 <b>구성 수량과 마스터 옵션명만</b>
 * 전달한다.
 *
 * <p>⚠️ 가격·재고·옵션 식별자는 여기서 받지 않는다 — 커밋 시 마켓을 한 번 더 조회해 서버가 확정한다
 * (미리보기~커밋 사이의 변경 반영 + 클라이언트 값 신뢰 금지).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Import an existing marketplace product as a channel cell of this master")
public class ListingImportRequest {

    @NotNull(message = "Seller ID cannot be null")
    @Schema(description = "Seller ID (the account's seller)", example = "3")
    private Long sellerId;

    @NotBlank(message = "Platform cannot be blank")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "Platform product ID cannot be blank")
    @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "1234567")
    private String platformProductId;

    @NotEmpty(message = "Options cannot be empty")
    @Valid
    @Schema(description = "One entry per marketplace option; the set must equal the marketplace's")
    private List<OptionSpec> options;

    /** 마켓 옵션 하나에 대응하는 입력: 어떤 마스터 옵션에 붙을지(구성)와 새로 만들 때 쓸 이름. */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Per-marketplace-option composition input")
    public static class OptionSpec {

        @Schema(description = "Marketplace option id (Coupang vendorItemId); null = match by itemName",
                example = "8123")
        private String vendorItemId;

        @NotBlank(message = "Item name cannot be blank")
        @Schema(description = "Option name as shown on the marketplace", example = "6입")
        private String itemName;

        @NotBlank(message = "Master option name cannot be blank")
        @Schema(description = "Name for the master option created when no existing BOM matches (D11)",
                example = "6입")
        private String masterOptionName;

        @NotEmpty(message = "Components cannot be empty")
        @Valid
        @Schema(description = "Quantity per master component — every component must be present (D9)")
        private List<Component> components;
    }

    /** 구성 한 줄: 마스터 구성품 하나의 수량(D9 = 전 구성품 고정, 수량만 입력). */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Quantity of one master component in this option")
    public static class Component {

        @NotNull(message = "Product ID cannot be null")
        @Schema(description = "Product id (must be one of the master's components)", example = "11")
        private Long productId;

        @NotNull(message = "Quantity cannot be null")
        @Schema(description = "Quantity (>= 1)", example = "6")
        private Integer quantity;
    }
}
