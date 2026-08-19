package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Set a zone's images to exactly this ordered list (drag/select/reorder in one idempotent call).
 * An empty list clears the zone (FEATURE_2608_06 / 37).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Pool image IDs mapped to a zone, in the desired order (empty clears the zone)")
public class MasterZoneImagesRequest {

    @NotNull
    @Schema(description = "Pool image IDs for this zone, in order (must all belong to the master's pool)")
    private List<Long> imageIds;
}
