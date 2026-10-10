package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Outcome of the 11st fee notice import (FEATURE_2610_10 / D22 · D27 ⑨ · D28).
 *
 * <p>{@code leavesUpdated} counts the leaves written (borrowed ones included). A row in
 * {@code unmatchedCategories} and a name in {@code unmatchedExceptions} wrote nothing.</p>
 */
@Getter
@Builder
@Schema(description = "11st fee notice import result")
public class ElevenstFeeImportResult {

    /** Leaves whose commission was written from the table (including the D28 borrow). */
    private final int leavesUpdated;

    /**
     * Table rows not saved: fee not N%, category not in the 11st tree or with no leaf under it, or two rows on
     * one category — plus the D28 item ({@code group == null}) when the 「욕실용품」 row is missing.
     */
    private final List<Item> unmatchedCategories;

    /**
     * Exception names not found right under their category, or found with no leaf under them — those leaves keep
     * the category value (D29).
     */
    private final List<Item> unmatchedExceptions;

    /** 11st top-level categories that took another row's value (D28). */
    private final List<Borrowed> borrowed;

    /**
     * One unmatched row or exception name.
     *
     * @param group         1st column text; {@code null} for the D28 「no 욕실용품 row」 item
     * @param category      2nd column text (for D28: the borrowing top-level name)
     * @param exceptionName the exception name; {@code null} for a row item
     * @param fee           the table percent as shown (e.g. {@code "13%"}); {@code null} for the D28 item
     */
    public record Item(String group, String category, String exceptionName, String fee) {
    }

    /**
     * @param category     the 11st top-level name that borrowed (e.g. 「구강/면도」)
     * @param borrowedFrom the table row whose value it took (e.g. 「욕실용품」)
     * @param fee          that row's percent as shown
     */
    public record Borrowed(String category, String borrowedFrom, String fee) {
    }
}
