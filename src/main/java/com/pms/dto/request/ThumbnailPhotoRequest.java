package com.pms.dto.request;

import com.pms.service.collage.CollageStage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Choose (or clear) one stage's thumbnail photo (FEATURE_2610_32 D23).
 *
 * <p>⚠️ {@code imageId} is intentionally NOT {@code @NotNull} — null clears this stage's own choice (the stage then
 * uses the upper stage).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Stage + pool image ID to use as that stage's thumbnail photo; null imageId clears it")
public class ThumbnailPhotoRequest {

    @Schema(description = "MASTER · SELLER · LISTING", example = "LISTING")
    private CollageStage stage;

    @Schema(description = "SELLER stage: the seller (must have a listing of this master)", example = "7")
    private Long sellerId;

    @Schema(description = "LISTING stage: the listing (must belong to this master)", example = "42")
    private Long listingId;

    @Schema(description = "Pool image ID of this master, or null to clear", example = "5")
    private Long imageId;
}
