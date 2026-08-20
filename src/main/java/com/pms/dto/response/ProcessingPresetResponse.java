package com.pms.dto.response;

import com.pms.domain.ImageOp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Image-processing preset (op array)")
public class ProcessingPresetResponse {

    @Schema(description = "Preset ID", example = "1")
    private Long id;

    @Schema(description = "Preset name", example = "쿠팡 워터마크")
    private String name;

    @Schema(description = "Ordered image ops (v1: overlay)")
    private List<ImageOp> operations;

    @Schema(description = "Active flag", example = "true")
    private Boolean active;
}
