package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Channel-add result (FEATURE_2608_06 / 3b'): the new DRAFT listing id + status + its freshly generated
 * assets (thumbnail/detail/option prices). Reuses {@link GeneratedProductResponse} from 3b-2.
 */
@Getter
@Builder
@Schema(description = "Channel-add result: new DRAFT listing + generated assets")
public class ChannelAddResponse {

    @Schema(description = "New product listing (cell) ID", example = "10")
    private Long productListingId;

    /**
     * ⚠️ DRAFT 는 채널추가 경로에서만이다 — 2609_22 가져오기는 마켓의 현재 상태(대개 SELLING)를 그대로 싣는다.
     */
    @Schema(description = "Lifecycle status: DRAFT for channel-add, the market's mapped status for import",
            example = "DRAFT")
    private String status;

    @Schema(description = "Auto-generated assets (thumbnail, detail, per-option selling prices)")
    private GeneratedProductResponse generated;

    /**
     * 2609_22/D15: 가져오기에서 마켓 카테고리가 마스터의 표준 카테고리와 다를 때의 고정 경고 문구.
     * 채널추가 경로에서는 언제나 {@code null} 이다.
     */
    @Schema(description = "Category mismatch warning (import only); null when there is nothing to warn about")
    private String categoryWarning;
}
