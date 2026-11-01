package com.pms.dto.request;

import java.util.List;

/**
 * 「판매자 바꾸기」 of the daily in/out view (FEATURE_2611_01 D7 · D14 · D15).
 *
 * @param sellerId       the new seller (required — never back to 「미정」)
 * @param movementIds    incoming rows with a decided seller and no receipt (null = none)
 * @param shareRecordIds 「영수증 없음」 shares of undecided intake rows, one purchase record each (null = none)
 */
public record SellerChangeRequest(
        Long sellerId,
        List<Long> movementIds,
        List<Long> shareRecordIds
) {}
