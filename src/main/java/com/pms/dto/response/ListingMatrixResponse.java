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
    }
}
