package com.pms.dto.response;

import java.time.LocalDate;

/**
 * One lot 「일별 입출고 내역」 [입고로 채우기] offers (FEATURE_2611_22 D15).
 *
 * @param sellerName the lot owner; null = 「영수증 미등록」 (an undecided lot)
 * @param quantity   what is left of the lot, 1 or more
 */
public record FillLotView(
        Long lotId,
        LocalDate movedOn,
        String sellerName,
        int quantity
) {}
