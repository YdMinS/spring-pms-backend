package com.pms.service.stock;

import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import com.pms.dto.response.StockMovementView;

import java.time.LocalDate;
import java.util.List;

/**
 * Physical stock ledger — check-in, return check-in, disposal, adjustment and the derived balance
 * (FEATURE_2609_28 / PLAN D5~D10, D14).
 *
 * <p>⚠️ <b>Nothing may write here without a person confirming it</b> (2609_28 D18, revised by
 * PLAN 2609_29 D2). Order sync, schedulers and shipment hooks are still forbidden: every row means
 * "a person confirmed the goods moved", and the moment something automatic writes here the ledger
 * stops describing the warehouse and the feature is worthless. The [입고] click on the purchase list
 * IS that confirmation — {@code PurchaseListService} is therefore a legitimate caller, and the only
 * one besides the controller.
 *
 * <p>Outbound movements ({@code STOCK_OUT}) are not recorded here — they start from an order and get
 * their own flow.
 */
public interface StockLedgerService {

    /** Records one human-confirmed movement. Sign, unit price, location and createdBy are server-owned. */
    StockMovementView record(StockMovementRequest request);

    /**
     * On-hand per (product × seller), derived from the ledger sum (D14 revised by 2609_29 D5).
     * Blank keyword behaves as "no filter"; {@code sellerId} null = every seller.
     */
    List<StockBalanceView> balances(Long productId, Long sellerId, String keyword);

    /**
     * Ledger rows for a product / seller / period. Both dates optional — defaults to the last 30 days.
     * {@code sellerId} null = every seller.
     */
    List<StockMovementView> history(Long productId, Long sellerId, LocalDate from, LocalDate to);

    /** Purchases with goods still not checked in — the source of {@code purchaseRecordId}. */
    List<PurchaseCandidateView> purchaseCandidates(Long productId);

    /** Return claims with goods still not checked back in — the source of {@code orderClaimId}. */
    List<ReturnCandidateView> returnCandidates();
}
