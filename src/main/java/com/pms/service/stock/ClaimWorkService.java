package com.pms.service.stock;

import com.pms.dto.request.ClaimWorkRequest;
import com.pms.dto.response.ClaimWorkView;
import com.pms.dto.response.ShippedOrderView;

import java.util.List;

/**
 * 「반품 재입고」 · 「교환 발송」 lookups and saves (FEATURE_2611_34 D35 ① ~ ④ · ⑥ · ⑦).
 *
 * <p>Lookups answer one {@link ClaimWorkView} per claim (header + products with required · done · remaining counts).
 * Saves write every line through {@link WorkLineRecorder} in one transaction — one rejected line saves nothing (D34).
 *
 * <p>Usage:
 * <pre>{@code
 * List<ClaimWorkView> claims = claimWorkService.byReturnInvoice("1234-5678-9012");
 * claimWorkService.returnIn(new ClaimWorkRequest(movedOn, lines));
 * }</pre>
 *
 * <p>❌ Do not filter lookups by claim status or collect status (D19) — the only exception is
 * {@link #openExchanges()} dropping finished exchanges (D42 round 5).
 */
public interface ClaimWorkService {

    /** ① claims whose pickup invoice matches; 404 when none. */
    List<ClaimWorkView> byReturnInvoice(String value);

    /** ② claims on the order lines of the box holding this outbound invoice; 404 when no box or no claim. */
    List<ClaimWorkView> byParcelInvoice(String value);

    /** ③a orders shipped in the last 14 days (by order date), pending claims first. */
    List<ShippedOrderView> shippedOrders();

    /** ③b claims on one order; 404 when none. */
    List<ClaimWorkView> byOrder(Long orderId);

    /** ⑥a exchange claims whose reship invoice matches; 404 when none. */
    List<ClaimWorkView> byReshipInvoice(String value);

    /** ⑥b exchange claims not finished yet, newest first. */
    List<ClaimWorkView> openExchanges();

    /** ④ return check-in save. */
    void returnIn(ClaimWorkRequest request);

    /** ⑦ exchange shipment save. */
    void exchangeShip(ClaimWorkRequest request);
}
