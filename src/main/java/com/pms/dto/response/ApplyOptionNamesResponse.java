package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Result of [옵션명 일괄 적용] (FEATURE_2609_22 / D4): every cell of a master gets its linked options'
 * names reset to the master's, and {@code optionNameSource} back to {@code AUTO}.
 *
 * <p>Channel-only options (2609_22/D2) are skipped — they have no master name to take. A cell where the
 * reset would produce two options with the same name is skipped <b>as a whole</b> and reported in
 * {@code warnings}; the other cells are still applied (never fail the batch).</p>
 */
@Getter
@Builder
@Schema(description = "Bulk option-name reset result")
public class ApplyOptionNamesResponse {

    @Schema(description = "Cells whose options were actually changed", example = "2")
    private int updatedCells;

    @Schema(description = "Options renamed across those cells", example = "5")
    private int updatedOptions;

    @Schema(description = "Cells skipped because the reset would duplicate a name inside them")
    private List<String> warnings;
}
