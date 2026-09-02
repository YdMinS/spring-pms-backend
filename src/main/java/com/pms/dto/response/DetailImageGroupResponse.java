package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A detail image group with its usage counts (FEATURE_2609_03). The counts let the catalog screen explain
 * why a delete is blocked without a second round trip.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Detail image group (tenant-wide zone catalog entry)")
public class DetailImageGroupResponse {

    @Schema(description = "Group ID", example = "1")
    private Long id;

    @Schema(description = "Immutable zone id (= master_image_zone_assignment.zone_id)", example = "product_photos")
    private String code;

    @Schema(description = "Display name", example = "제품 사진")
    private String name;

    @Schema(description = "Position in the catalog (creation order)", example = "0")
    private Integer sortOrder;

    /** Active templates referencing this code. Non-zero blocks delete. */
    @Schema(description = "Active templates using this group", example = "2")
    private Integer templateCount;

    /** Mapped master photos. NOT a delete blocker — used for the confirm copy only. */
    @Schema(description = "Master photos mapped to this group", example = "137")
    private Integer imageCount;

    /** Up to 5 names, for the block reason shown on the delete attempt. */
    @Schema(description = "Names of the active templates using this group (max 5)")
    private List<String> usedByTemplateNames;
}
