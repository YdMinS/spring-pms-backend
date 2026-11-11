package com.pms.service.lotteon;

import com.pms.dto.request.LotteonDeliveryAppointmentRequest;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonOrderChoicesResponse;

import java.util.List;

/**
 * LotteON order-detail reads that back the seller actions (FEATURE_2611_23 / D19 ⑥-5 · D58), the LotteON code lists
 * (D58) and the ADMIN write [발송 약정일 안내] ([138], D26 · R25).
 */
public interface LotteonOrderSellerService {

    /**
     * Every line of the same order, sales channel and delivery bundle as {@code orderItemId} (line id order) with
     * its [140] delivery records — [140] of the order is read once (D19 ② · R17 · R19).
     *
     * @throws IllegalArgumentException line not found · not a LotteON line with a bundle and a mirror (→ 400, R18)
     */
    List<LotteonInvoiceRecordsResponse> invoiceRecords(Long orderItemId);

    /** The five LotteON code lists as they are (D58). */
    LotteonOrderChoicesResponse choices();

    /**
     * [138] shipping appointment of every paid · preparing line (with a mirror) of the same order and sales channel as
     * {@code orderItemId}, line id order — one call, nothing stored locally (D26 · R25).
     *
     * @throws IllegalArgumentException line not found · not a LotteON line with a bundle and a mirror · line not paid
     *                                  or preparing · reason (or the making-delay detail) not in the list (→ 400)
     */
    void deliveryAppointment(Long orderItemId, LotteonDeliveryAppointmentRequest request);
}
