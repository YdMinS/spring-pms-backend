package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 이미 연결된 채널 셀의 마켓 이미지 URL(온보딩, 2026-09-19).
 *
 * <p>가져오기 미리보기({@code ListingImportPreviewResponse}·{@code MasterFromChannelPreviewResponse})가
 * 싣는 두 이미지 목록과 <b>같은 의미·같은 순서</b>다. 다른 점은 대상뿐이다 — 저쪽은 아직 연결되지 않은
 * 마켓 상품을, 이쪽은 <b>이미 우리 셀이 된</b> 상품을 읽는다.</p>
 *
 * <p>🔴 <b>URL 만 내려준다.</b> 이미지를 내려받지도, 우리 저장소로 옮기지도, 자산으로 등록하지도 않는다.</p>
 */
@Getter
@Builder
@Schema(description = "Marketplace image URLs of an already-linked channel cell (read-only)")
public class ChannelImageResponse {

    @Schema(description = "Our channel cell id", example = "1024")
    private Long productListingId;

    @Schema(description = "Marketplace platform of the cell", example = "COUPANG")
    private String platform;

    @Schema(description = "Marketplace product id read (Coupang sellerProductId)", example = "222333444")
    private String platformProductId;

    @Schema(description = "Marketplace product name as it currently stands", example = "노브랜드 생수 2L 6입")
    private String productName;

    /**
     * 대표/썸네일 이미지 URL, 마켓 순서 그대로.
     *
     * <p>🔴 <b>마켓 가공본이다</b>(문구·테두리가 얹혀 있다) — 제품 사진으로 그대로 쓸 수 없다.
     * {@link #detailImages} 와 <b>절대 합치지 말 것</b>: 합치는 순간 소비자가 가공본을 구분할 수 없게 된다.</p>
     */
    @Schema(description = "Marketplace thumbnail image URLs (PROCESSED by the market — text/borders baked in)")
    private List<String> thumbnailImages;

    /**
     * 상세 페이지 콘텐츠 이미지 URL, 설명 흐름 순서 그대로. 원본에 가까운 제품 사진은 여기 있다.
     * 어떤 이미지가 제품 사진인지의 판정은 <b>소비자(온보딩 스크립트)의 몫</b>이다.
     */
    @Schema(description = "Detail-page image URLs in the original explanation order (near-original photos)")
    private List<String> detailImages;
}
