package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 마켓 상품 한 건을 그대로 보여주는 응답(2609_67 — 물품 등록 참고 패널). 사람이 <b>보면서 옮겨 적는</b> 재료다.
 *
 * <p><b>읽기 전용</b>: 저장 0회이고 <b>아무 판정도 하지 않는다</b>(PLAN/D4) — 이미 우리 셀로 연결된 상품도,
 * 옵션이 없는 임시저장 상품도 그대로 200 으로 내려온다. 미리보기
 * ({@link MasterFromChannelPreviewResponse})와 값의 뜻은 같지만 그쪽은 마스터를 만들 수 있는지 판정한다.</p>
 *
 * <p>🔴 속성값은 <b>단위가 떨어져 있을 수도, 붙어 있을 수도 있다</b>(어댑터의 {@code stripUnit} 은 접미사가 그
 * 속성의 기본 단위와 같을 때만 떼고, 카테고리 메타를 건너뛰면 전부 원문이다). 서버는 여기서 단위를
 * <b>되붙이지 않는다</b>(PLAN/D7) — 값의 생김새로 세 갈래를 가르는 것은 화면의 몫이다.</p>
 */
@Getter
@Builder
@Schema(description = "One marketplace product, read verbatim for the product-register reference panel")
public class ChannelProductDetailResponse {

    @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "222333444")
    private String platformProductId;

    @Schema(description = "Marketplace product name", example = "노브랜드 생수 2L 6입")
    private String productName;

    @Schema(description = "Marketplace brand; null when absent", nullable = true, example = "노브랜드")
    private String brand;

    @Schema(description = "Marketplace status mapped to our lifecycle status", example = "SELLING")
    private ListingStatus status;

    @Schema(description = "Marketplace leaf category code, display only", nullable = true, example = "73170")
    private String categoryCode;

    @Schema(description = "Notice item group (Coupang noticeCategoryName)", nullable = true, example = "가공식품")
    private String noticeGroup;

    /** 고시는 품목군 단위라 옵션에 따라 갈리지 않는다 — 상품 레벨로 한 번만 내린다. */
    @Schema(description = "Product-info disclosure values (product-level)")
    private Map<String, String> notices;

    /**
     * 대표/썸네일 이미지 URL, 마켓 순서 그대로.
     *
     * <p>🔴 <b>마켓 가공본이다</b>(문구·테두리가 얹혀 있다). {@link #detailImages} 와 <b>절대 합치지 말 것</b> —
     * 합치는 순간 사용자가 가공본과 제품 사진을 구분할 수 없게 된다.</p>
     */
    @Schema(description = "Marketplace thumbnail image URLs (PROCESSED by the market — text/borders baked in)")
    private List<String> thumbnailImages;

    /** 상세 콘텐츠 이미지 URL, 설명 흐름 순서 그대로. 원본에 가까운 제품 사진은 여기 있다. */
    @Schema(description = "Detail-page image URLs in the original explanation order (near-original photos)")
    private List<String> detailImages;

    @Schema(description = "Options as they exist on the marketplace")
    private List<Option> options;

    /** 마켓 옵션 하나. 공통 속성 분리는 하지 않는다 — 옵션별 속성을 그대로 내린다. */
    @Getter
    @Builder
    @Schema(description = "One marketplace option")
    public static class Option {

        @Schema(description = "Option name on the marketplace", example = "6입")
        private String itemName;

        @Schema(description = "Current selling price on the marketplace; null when unset",
                nullable = true, example = "12900")
        private BigDecimal salePrice;

        @Schema(description = "Stock (Coupang maximumBuyCount); null = unset", nullable = true, example = "50")
        private Integer stockQuantity;

        /** 🔴 옵션마다 다른 값이다(실측 {@code 수량 → "6개"}) — 상품 레벨로 올리지 말 것. */
        @Schema(description = "Category attributes stored on the marketplace for THIS option")
        private Map<String, String> attributes;
    }
}
