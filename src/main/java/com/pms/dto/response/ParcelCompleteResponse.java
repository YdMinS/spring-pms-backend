package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of completing a box (FEATURE_2609_40 / PLAN D4 · D15).
 *
 * <p>🔴 It carries no saving amount — {@code expected − actual} can always be subtracted, and storing or sending it
 * separately makes room for the three values to disagree (D4). The summary screen (2609_41) computes from these three
 * fields too.
 *
 * <p>⚠️ A repeated request for a completed box <b>creates nothing</b> and returns the stored values as they are (D15).
 *
 * @param expectedBoxCost      the box cost that went into the selling prices. {@code null} when the setting cannot be
 *                             read — an empty summary is better than a blocked outbound
 * @param actualBoxCost        the cost of the box actually used (0 for a reused box)
 * @param expectedDeliveryCost the delivery cost that went into the selling prices (input of 2609_41)
 * @param notices              lines like 「○○ 재고 N개 사용(채무 ₩M)」 for goods borrowed from another seller
 *                             (FEATURE_2610_19 D49 · D137). Empty on a repeated request for a completed box.
 */
public record ParcelCompleteResponse(Long parcelId, ParcelStatus status, int packedQty, List<String> notices,
                                     BigDecimal expectedBoxCost, BigDecimal actualBoxCost,
                                     BigDecimal expectedDeliveryCost) {
}
