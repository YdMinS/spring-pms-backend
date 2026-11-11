package com.pms.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One product line of a LotteON delivery bundle with its delivery records, for the LotteON invoice-update window
 * (FEATURE_2611_23 / D19 ② · ⑥-1 · ⑥-2 · R17).
 *
 * @param orderItemId order line id of the bundle (what the invoice-update request sends back)
 * @param productName {@code order_line.item_name}
 * @param records     the line's [140] delivery records in {@code procSeq} order — empty when none
 */
public record LotteonInvoiceRecordsResponse(Long orderItemId, String productName, List<Record> records) {

    /**
     * One [140] delivery record.
     *
     * @param procSeq       the record's {@code procSeq}
     * @param invoiceNumber {@code invcNo}, null when blank
     * @param carrierCode   {@code dvCoCd}
     * @param carrierName   the LotteON carrier name of the code (the code itself when not listed); null with no code
     * @param statusCode    {@code odPrgsStepCd}
     * @param statusName    LotteON's own step name (R17)
     * @param statusAt      {@code dvTrcStatDttm} (KST)
     */
    public record Record(String procSeq, String invoiceNumber, String carrierCode, String carrierName,
                         String statusCode, String statusName, LocalDateTime statusAt) {
    }
}
