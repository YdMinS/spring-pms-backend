package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 마켓 상품 이름 검색 결과(2609_67 — 물품 등록 참고 패널). <b>읽기 전용</b>: 이 응답을 만드는 동안 저장은
 * 한 번도 일어나지 않고, 연결 여부·재사용 가능 여부 같은 <b>판정도 하지 않는다</b>(PLAN/D4).
 *
 * <p>🔴 <b>사진이 없다.</b> 쿠팡 목록 응답에 이미지 필드가 없어서다 — 사진은 단건 조회
 * ({@link ChannelProductDetailResponse})로만 온다. 화면은 후보를 고른 뒤 한 건을 더 읽는 2단계다.</p>
 */
@Getter
@Builder
@Schema(description = "Marketplace product name-search results (read-only, no photos)")
public class ChannelProductSearchResponse {

    @Schema(description = "Candidates in the marketplace's own order")
    private List<Item> items;

    /**
     * 이어보기 키. 🔴 <b>null 이면 마지막 페이지</b>다 — 빈 문자열을 내리지 않는다(프론트의 [더 보기] 판정 기준).
     */
    @Schema(description = "Paging key for the next page; null means this was the last page", nullable = true)
    private String nextToken;

    /** 후보 한 건. 사진·옵션·속성은 단건 조회에만 있다. */
    @Getter
    @Builder
    @Schema(description = "One marketplace product candidate")
    public static class Item {

        @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "222333444")
        private String platformProductId;

        @Schema(description = "Marketplace product name", example = "노브랜드 생수 2L 6입")
        private String productName;

        @Schema(description = "Marketplace brand; null when absent", nullable = true, example = "노브랜드")
        private String brand;

        @Schema(description = "Marketplace status mapped to our lifecycle status", example = "SELLING")
        private ListingStatus status;

        /** 마켓이 준 문자열 그대로다 — 서버가 파싱하거나 형식을 바꾸지 않는다. */
        @Schema(description = "Marketplace creation timestamp, verbatim", example = "2026-08-01T13:20:11")
        private String createdAt;
    }
}
