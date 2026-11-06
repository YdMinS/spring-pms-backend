package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Outcome of the LotteON commission CSV import (FEATURE_2610_24 / D46).
 *
 * <p>{@code leavesUpdated} counts the leaves written. {@code unmatchedRows} = CSV lines whose path is not exactly one
 * imported category — nothing was written from them. {@code leavesWithoutFee} = leaves that neither they nor any
 * upper category have a line for — they keep their stored value.</p>
 */
@Getter
@Builder
@Schema(description = "LotteON commission CSV import result")
public class LotteonFeeImportResult {

    private final int leavesUpdated;

    private final List<Item> unmatchedRows;

    private final int leavesWithoutFee;

    /**
     * One unmatched CSV line.
     *
     * @param path the category path as written in the file
     * @param fee  the percent as written (e.g. {@code "7.00%"})
     */
    public record Item(String path, String fee) {
    }
}
