package com.pms.dto.response;

import java.util.List;

/**
 * Result of one receipt check-in (FEATURE_2610_18 D28).
 *
 * @param lotIds the stock rows a receipt check-in wrote, in line order — the 「채울까요?」 window asks for them
 *               (FEATURE_2611_22 D3 ①); empty for every other call
 */
public record ReceiptCreateResult(Long receiptId, List<Long> lotIds) {

    public ReceiptCreateResult(Long receiptId) {
        this(receiptId, List.of());
    }
}
