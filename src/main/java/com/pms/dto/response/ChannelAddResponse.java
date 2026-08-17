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

    @Schema(description = "Lifecycle status (always DRAFT for channel-add)", example = "DRAFT")
    private String status;

    @Schema(description = "Auto-generated assets (thumbnail, detail, per-option selling prices)")
    private GeneratedProductResponse generated;
}
