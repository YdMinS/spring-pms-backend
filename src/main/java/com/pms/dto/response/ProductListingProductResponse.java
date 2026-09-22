package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 셀 옵션의 구성품 1줄 (응답 전용).
 *
 * <p>🔴 <b>출처는 마스터다</b>(FEATURE_2609_71). {@code product_listing_product} 테이블은 사라졌고, 이 DTO 는
 * {@code CellBomResolver.Line}(= {@code master_product_option_item})으로 채워진다. 그래서 {@code id} 는 그
 * 마스터 옵션 item 의 id 이고, {@code productListingOptionId} 는 그것을 읽어 간 셀 옵션의 id 다 — 두 값이
 * 같은 테이블에서 나오지 않는다.
 *
 * <p>⚠️ 이름을 그대로 둔 이유: 웹·모바일이 이 모양을 이미 쓰고 있다. 응답 모양을 유지하는 것이
 * 2609_71 의 전제였다(Expand-Contract).
 *
 * <p>⚠️ 채널 전용 옵션(마스터 옵션 미연결)은 구성품을 <b>알 수 없다</b> — 빈 목록으로 나가지만
 * 「구성품 0개」라는 뜻이 아니다({@code CellBomResolver.Bom#unmapped}).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Cell option composition line (sourced from the master product option)")
public class ProductListingProductResponse {

    @Schema(description = "Source master_product_option_item ID", example = "1")
    private Long id;

    @Schema(description = "Product listing option ID (parent)", example = "1")
    private Long productListingOptionId;

    @Schema(description = "Product ID", example = "1")
    private Long productId;

    @Schema(description = "Product name", example = "Galaxy S21")
    private String productName;

    @Schema(description = "Quantity of product in this option", example = "3")
    private Integer quantity;
}
