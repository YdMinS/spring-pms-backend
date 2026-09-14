package com.pms.dto.response;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Channel coverage matrix for one master product (FEATURE_2608_06 / 3a).
 *
 * <p>Rows = every marketplace account of the tenant (left side). {@code cell} is non-null when that
 * account's (seller, platform) has a listing under this master (right side), null otherwise.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Channel coverage matrix for a master product")
public class ListingMatrixResponse {

    @Schema(description = "Master product ID", example = "1")
    private Long masterId;

    @Schema(description = "Master product name", example = "Galaxy S21 Bundle")
    private String masterName;

    @Schema(description = "One row per marketplace account (tenant-wide)")
    private List<MatrixRow> rows;

    /**
     * 마스터 표준 카테고리를 그 플랫폼 코드로 해석한 이름(없으면 null) — 2609_45/D13 의 "A → B 로 변경됩니다"
     * 안내에 쓸 B 쪽 이름이다. 셀마다 같은 값이라 행이 아니라 매트릭스 최상위에 둔다.
     */
    @Schema(description = "Name of the master's standard category (null when unresolvable)", example = "즉석밥")
    private String masterCategoryName;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Matrix row: an account × its coverage cell for this master")
    public static class MatrixRow {

        @Schema(description = "Seller ID", example = "3")
        private Long sellerId;

        @Schema(description = "Seller display name", example = "행복상회")
        private String sellerName;

        @Schema(description = "Platform identifier", example = "COUPANG")
        private String platform;

        @Schema(description = "Marketplace account ID", example = "7")
        private Long accountId;

        @Schema(description = "Marketplace account alias (nullable)", example = "메인계정")
        private String accountLabel;

        @Schema(description = "True when this account has a listing under the master", example = "true")
        private boolean registered;

        @Schema(description = "Coverage cell (null when not registered)")
        private MatrixCell cell;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Coverage cell: the listing registered on this channel")
    public static class MatrixCell {

        @Schema(description = "Product listing ID", example = "12")
        private Long productListingId;

        @Schema(description = "Display name (노출상품명) — the listing's manual name", example = "행복상회 갤럭시 번들")
        private String name;

        @Schema(description = "Platform product ID", example = "12345678")
        private String platformProductId;

        @Schema(description = "Selling price of the (single-SKU) listing option", example = "12999.99")
        private BigDecimal sellingPrice;

        @Schema(description = "Auto-generated registration name from this channel's active options (67)", example = "노브랜드 생수 x 6")
        private String registrationName;

        /**
         * 이 셀의 실제 등록 상태({@link com.pms.domain.ListingStatus} 이름). 종전에는 필드가 없어서
         * 프론트가 {@code platformProductId} 유무로 DRAFT/SUBMITTED 를 <b>추정</b>했고, 그 결과
         * 승인완료(SELLING)·반려(REJECTED)된 셀이 계속 "승인 대기중"으로 보였다.
         * {@code fetchStatus} 는 이미 상태를 DB 에 저장하고 있었으므로 노출만 하면 된다.
         *
         * <p>⚠️ enum 이름을 그대로 준다 — 한글 라벨은 화면 몫이다(백엔드가 문구를 정하면 두 곳이 갈라진다).
         */
        @Schema(description = "Listing status (DRAFT/SUBMITTED/SELLING/REJECTED/SUSPENDED)", example = "SELLING")
        private String status;

        /** 이 셀이 <b>실제로</b> 쓰는 마켓 카테고리 코드(2609_45/D9). 해석 불가면 null. */
        @Schema(description = "Marketplace category code this cell actually uses", example = "73170")
        private String categoryCode;

        /** 그 카테고리의 이름 — 화면 표시용. */
        @Schema(description = "Name of the category this cell actually uses", example = "즉석밥")
        private String categoryName;

        /**
         * {@code true} = 채널 자기 카테고리, {@code false} = 마스터 카테고리.
         *
         * <p>⚠️ {@code platformCategoryCode != null} 로 판정하지 말 것 — 가져오기는 카테고리가 같아도 코드를
         * 저장하므로(2609_45/D10-1) 기존 셀 전부에 배지가 뜬다. 수수료가 없어 마스터로 폴백한 경우(D11)도
         * {@code false} 다. 화면 판정과 전송 판정이 갈리면 화면이 거짓말을 한다.</p>
         */
        @Schema(description = "True when the cell uses its own (channel) category", example = "true")
        private boolean usesOwnCategory;
    }
}
