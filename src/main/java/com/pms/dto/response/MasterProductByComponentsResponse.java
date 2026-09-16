package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One master product that is already built from the exact same component (product) set.
 *
 * <p>A master's identity is its component set — quantity differences belong to that master's options
 * (see {@code MasterProductServiceImpl#assertCoversComponents}). This response carries just enough for
 * the create screen to say "이미 있습니다" and link to the existing master.</p>
 *
 * <p>Soft-deleted masters ({@code active = false}) are reported too, flagged by {@code active} — a
 * hidden duplicate is exactly the case where a user would otherwise create a second one.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Existing master product sharing the exact same component set")
public class MasterProductByComponentsResponse {

    @Schema(description = "Master product ID", example = "5")
    private Long id;

    @Schema(description = "Master product name", example = "노브랜드 생수 묶음")
    private String name;

    @Schema(description = "Activation flag (false = soft deleted, hidden from the list screen)", example = "true")
    private Boolean active;

    @Schema(description = "How many options the master already has", example = "3")
    private Integer optionCount;
}
