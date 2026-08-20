package com.pms.dto.request;

import com.pms.domain.ImageOp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create/update payload for a {@link com.pms.domain.ProcessingPreset} (FEATURE_2608_08).
 *
 * <p>PATCH treats null fields as "keep existing" (partial update), so the same DTO serves create and
 * update. No {@code @Setter} (Builder only).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Processing preset create/update request")
public class ProcessingPresetRequest {

    @Schema(description = "Preset name", example = "쿠팡 워터마크")
    private String name;

    @Schema(description = "Ordered image ops (v1: overlay)")
    private List<ImageOp> operations;

    @Schema(description = "Active flag; defaults to true on create", example = "true", nullable = true)
    private Boolean active;
}
