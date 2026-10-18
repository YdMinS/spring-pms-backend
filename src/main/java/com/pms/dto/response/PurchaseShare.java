package com.pms.dto.response;

import java.time.LocalDate;

/**
 * One seller's share of a product on the purchase list (FEATURE_2610_09 D2 · D57 · D59 · D80).
 *
 * <p>state = OPEN (to buy) · SKIPPED_TODAY (「내일 매입」 today) · PURCHASED (「매입 완료」) · NOT_NEEDED (「필요 없음」) ·
 * NEED_ZERO (「필요 0」). A share whose needed quantity has always been 0 is not sent at all (D80).</p>
 *
 * @param purchasedQty this seller's purchase record total (D2)
 * @param remainingQty neededQty − purchasedQty (negative = bought more than needed)
 * @param targetMet    purchasedQty ≥ neededQty — the 「목표 수량 매입 완료」 tag (D57 ①)
 * @param checkId      the screen-made record behind SKIPPED_TODAY / PURCHASED / NOT_NEEDED — the undo target; null otherwise
 * @param checkedOn    the processing day of PURCHASED / NOT_NEEDED / NEED_ZERO (D63 · D85); null otherwise
 */
public record PurchaseShare(
        Long sellerId,
        String sellerName,
        int neededQty,
        int purchasedQty,
        int remainingQty,
        boolean targetMet,
        String state,
        Long checkId,
        LocalDate checkedOn
) {}
