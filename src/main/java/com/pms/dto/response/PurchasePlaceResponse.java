package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One purchase place on the list screen (FEATURE_2609_76). {@code productCount} lets the screen explain a
 * blocked delete without a second round trip (D9).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Purchase place (tenant-wide list entry)")
public class PurchasePlaceResponse {

    @Schema(description = "Purchase place ID", example = "1")
    private Long id;

    @Schema(description = "Display name", example = "이마트")
    private String name;

    @Schema(description = "Position in the list (creation order)", example = "0")
    private Integer sortOrder;

    /** Active products using this place. Non-zero blocks delete (D9). */
    @Schema(description = "Active products using this place", example = "12")
    private Integer productCount;
}
