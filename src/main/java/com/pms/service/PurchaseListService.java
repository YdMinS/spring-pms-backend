package com.pms.service;

import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.ManualSellerRequest;
import com.pms.dto.request.PurchaseCheckRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;

import java.time.LocalDate;
import java.util.List;

/**
 * Purchase list ("today's shopping list") business logic.
 *
 * <p>🔴 The seller is a view filter, never a sync scope (FEATURE_2610_09 D1 · D19): the extract always covers every
 * seller, and {@code sellerId} only narrows what a read returns. Numbers are per (product × seller) share (D2 · D22).
 *
 * <p>🔴 Which tab a row sits in is decided by the buyer's processing records, not by the numbers
 * (D57 · D78 · D80): PURCHASED / NOT_NEEDED / NEED_ZERO → completed, TOMORROW (today) → 「오늘 넘김」, nothing → list.
 *
 * <p>🔴 A check-in is one event (PLAN 2609_29 D1): {@link #addPurchase} writes purchase_record (money) and, while the
 * stock link setting is on, stock_movement (goods) in the <b>same transaction</b> (FEATURE_2610_09 D65).
 *
 * Order sync belongs to order-sync — this service only reads loaded order_line rows.
 * Every purchase list operation goes through this service (no repository calls from the controller).
 *
 * @see com.pms.controller.PurchaseListController
 */
public interface PurchaseListService {

    /**
     * Expand paid orders by BOM and re-load shopping_list_item.autoQty idempotently; manualQty and purchase_record
     * stay. The reset and the reload both cover every seller (D19). Afterwards a share whose needed quantity fell
     * from above 0 to 0 gets a NEED_ZERO record, and one that rose above 0 again has it cancelled (D85).
     */
    void extract();

    /** Purchase list tab: rows to buy + 「오늘 넘김」 rows + unmapped orders. {@code sellerId} null = 「전체」. */
    PurchaseListResponse getList(Long sellerId);

    /**
     * Completed tab: rows whose shares are all processed (D57 · D78), filtered by the processing day
     * (D63 · D81 · D85). from/to inclusive, null = unbounded. {@code sellerId} null = 「전체」.
     */
    List<PurchaseProductGroup> getCompletedList(LocalDate from, LocalDate to, Long sellerId);

    /**
     * One check-in — the purchase record, plus a stock check-in while the stock link setting is on (D65 · D84).
     * A negative correction or {@code recordStock=false} skips stock; the result says so.
     *
     * <p>When the purchase price moves the base price, {@code Product.price} is updated too (2609_28 D4 ①). ⚠️ The
     * channel selling prices do <b>not</b> change here — only when the user confirms the cost propagation.
     */
    PurchaseRecordResult addPurchase(PurchaseRecordRequest request);

    /** Recent purchases of a product, newest first, {@code limit} rows; {@code sellerId} null = every seller (D7 · D25). */
    List<PurchaseRecordView> recentPurchases(Long productId, Long sellerId, int limit);

    /** Add a manual line or accumulate into the (product × seller) manual line (D11 · D16). */
    void addManual(ManualItemRequest request);

    /** Replace a manual line's manualQty (1 or more). Order lines → 400 (D23). */
    void adjustManual(Long itemId, ManualAdjustRequest request);

    /** Move a manual line to another seller. Order lines → 400; that seller already has a manual line → 409 (D13 · D24 · D26). */
    void changeManualSeller(Long itemId, ManualSellerRequest request);

    /** Delete a manual line. Order lines → 400 (D77). */
    void deleteManual(Long itemId);

    /** [매입 완료] · [오늘 매입 안 함] on one share — cancels that share's previous screen-made record first (D57 · D61 · D62). */
    void check(PurchaseCheckRequest request);

    /** [완료 취소] · [되돌리기] — marks the record cancelled; NEED_ZERO → 400 (D62 · D67 · D79). */
    void cancelCheck(Long checkId);
}
