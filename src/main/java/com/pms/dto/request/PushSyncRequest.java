package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for the layer-B batch push (FEATURE_2608_06 / 3d, POST /api/admin/listings/push-sync): the multi-select
 * set of listing ids to push. No {@code @Setter} — bound by Jackson via the no-args constructor + field access.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Listing ids to push to the market")
public class PushSyncRequest {

    @NotEmpty(message = "listingIds는 비어 있을 수 없습니다")
    @Schema(description = "Product listing ids to push", example = "[42, 43]")
    private List<Long> listingIds;
}
