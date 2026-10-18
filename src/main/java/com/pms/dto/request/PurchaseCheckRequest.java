package com.pms.dto.request;

import com.pms.domain.PurchaseCheckStatus;
import jakarta.validation.constraints.NotNull;

/**
 * [매입 완료] · [오늘 매입 안 함] on one (product × seller) share (FEATURE_2610_09 D57 · D59 · D61).
 * status = PURCHASED · NOT_NEEDED · TOMORROW. NEED_ZERO is written by the extract only — the service rejects it (400).
 */
public record PurchaseCheckRequest(
        @NotNull Long productId,
        @NotNull Long sellerId,
        @NotNull PurchaseCheckStatus status
) {}
