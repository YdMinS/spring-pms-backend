package com.pms.dto.response;

import java.util.Map;

/**
 * Outcome of a product merge (FEATURE_2609_69 / B).
 *
 * @param targetProductId   the surviving product
 * @param moved             rows migrated per table, keyed by the {@code TransferOptions} field name
 * @param droppedOnConflict rows that could not be migrated and were deleted instead —
 *                          {@code shoppingListItems} (the target already sits on that order line, and
 *                          quantities are never summed, PLAN D8) and {@code boxRecipes} (PLAN D12)
 * @param snapshotFileName  file NAME of the pre-merge snapshot (never the path — the directory is a
 *                          server-side setting and is not exposed to the client)
 */
public record MergeProductsResponse(
        Long targetProductId,
        Map<String, Integer> moved,
        Map<String, Integer> droppedOnConflict,
        String snapshotFileName
) {}
