package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * The two products of a planned merge (FEATURE_2611_09 / D28) — same pair as {@link MergeProductsRequest}.
 *
 * @param targetProductId the product that survives
 * @param sourceProductId the product that gets soft-deleted
 */
public record MergePreviewRequest(
        @NotNull Long targetProductId,
        @NotNull Long sourceProductId
) {}
