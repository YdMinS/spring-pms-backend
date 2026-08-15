package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master product response (Design 2)")
public class MasterProductResponse {

    @Schema(description = "Master product ID", example = "1")
    private Long id;

    @Schema(description = "Master product name", example = "Galaxy S21 Bundle")
    private String name;
}
