package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body of the master-wide approval refresh (FEATURE_2610_38 / D9, POST /api/admin/master-products/{id}/fetch-status):
 * the listings the user picked in the window. No {@code @Setter} — bound by Jackson via the no-args constructor +
 * field access.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Listing ids picked for the master-wide approval refresh")
public class MasterApprovalRefreshRequest {

    @NotEmpty(message = "productListingIds는 비어 있을 수 없습니다")
    @Schema(description = "Product listing ids of this master, in the order to refresh", example = "[42, 43]")
    private List<Long> productListingIds;
}
