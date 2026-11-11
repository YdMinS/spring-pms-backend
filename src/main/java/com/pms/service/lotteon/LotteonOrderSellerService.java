package com.pms.service.lotteon;

import com.pms.dto.response.LotteonInvoiceRecordsResponse;

import java.util.List;

/**
 * LotteON order-detail reads that back the seller actions (FEATURE_2611_23 / D19 ⑥-5 · D58).
 */
public interface LotteonOrderSellerService {

    /**
     * Every line of the same order, sales channel and delivery bundle as {@code orderItemId} (line id order) with
     * its [140] delivery records — [140] of the order is read once (D19 ② · R17 · R19).
     *
     * @throws IllegalArgumentException line not found · not a LotteON line with a bundle and a mirror (→ 400, R18)
     */
    List<LotteonInvoiceRecordsResponse> invoiceRecords(Long orderItemId);
}
