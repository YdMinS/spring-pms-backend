package com.pms.dto.request;

import java.time.LocalDate;

/**
 * The 「날짜 바꾸기」 window of 「일별 입출고 내역」 (FEATURE_2611_21 D9): one ledger row and its new day.
 *
 * @param movementId the row that was tapped — an order outbound row moves with every row of that order on that day (D4)
 * @param newMovedOn the new day; the same day as now = nothing changes
 */
public record DailyDateMoveRequest(
        Long movementId,
        LocalDate newMovedOn
) {}
