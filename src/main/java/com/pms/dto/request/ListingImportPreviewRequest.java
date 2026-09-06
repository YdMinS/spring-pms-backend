package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가져오기 미리보기 요청(FEATURE_2609_22 / D8): 마켓 상품 id 하나로 상품을 조회해 매핑 판정만 돌려준다.
 * <b>쓰기 0회</b> — 여기서 셀은 만들어지지 않는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Preview an existing marketplace product before importing it as a channel cell")
public class ListingImportPreviewRequest {

    @NotNull(message = "Seller ID cannot be null")
    @Schema(description = "Seller ID (the account's seller)", example = "3")
    private Long sellerId;

    @NotBlank(message = "Platform cannot be blank")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "Platform product ID cannot be blank")
    @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "1234567")
    private String platformProductId;
}
