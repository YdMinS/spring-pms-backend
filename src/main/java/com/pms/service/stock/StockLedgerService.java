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
 * <p>⚠️ <b>Only a controller may call this.</b> Order sync, schedulers and shipment hooks must NOT
 * (D18): every row of this ledger means "a person confirmed the goods moved". The moment something
 * automatic writes here, the ledger stops describing the warehouse and the feature is worthless.
 *
 * <p>Outbound movements ({@code STOCK_OUT}) are not recorded here — they start from an order and get
 * their own flow.
 */
public interface StockLedgerService {

    /** Records one human-confirmed movement. Sign, unit price, location and createdBy are server-owned. */
    StockMovementView record(StockMovementRequest request);

    /** On-hand per product, derived from the ledger sum (D14). Blank keyword behaves as "no filter". */
    List<StockBalanceView> balances(Long productId, String keyword);

    /** Ledger rows for a product / period. Both dates optional — defaults to the last 30 days. */
    List<StockMovementView> history(Long productId, LocalDate from, LocalDate to);

    /** Purchases with goods still not checked in — the source of {@code purchaseRecordId}. */
    List<PurchaseCandidateView> purchaseCandidates(Long productId);

    /** Return claims with goods still not checked back in — the source of {@code orderClaimId}. */
    List<ReturnCandidateView> returnCandidates();
}
