package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Outcome of the ESM fee table import (FEATURE_2610_26 / D14 · D63 · D67).
 *
 * <p>{@code leavesUpdated} counts the ESM leaves written (both commission columns, D64). {@code unusedRows} = table
 * rows no ESM leaf took (or whose fee is not a number). {@code emptyLeaves} = ESM leaves whose Gmarket commission is
 * still empty after the import — their price calculation keeps stopping at 「수수료 미설정」.</p>
 */
@Getter
@Builder
@Schema(description = "ESM fee table import result")
public class EsmFeeImportResult {

    private final int leavesUpdated;

    private final List<Row> unusedRows;

    private final List<Leaf> emptyLeaves;

    /**
     * @param major  the table's major-category text
     * @param middle the table's middle-category text
     * @param fee    the fee cell as written
     */
    public record Row(String major, String middle, String fee) {
    }

    /**
     * @param code ESM leaf category code
     * @param name ESM leaf category name
     */
    public record Leaf(String code, String name) {
    }
}
