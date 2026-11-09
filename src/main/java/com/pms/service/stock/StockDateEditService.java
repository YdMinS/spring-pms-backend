package com.pms.service.stock;

import com.pms.domain.PurchaseRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * The day of a stock ledger row corrected after the fact (FEATURE_2611_21) — 「일별 입출고 내역」 and the receipt header
 * edit. ADMIN only through its callers ({@code /api/admin/**}).
 *
 * <p>🔴 <b>The only place</b> that changes {@code stock_movement.moved_on} of a saved row. Quantity, source and the
 * number of rows never change (D11); every moved row gets one {@code stock_movement_date_edit} row (D7). One call is
 * one transaction — a refusal anywhere leaves nothing changed (D16).
 *
 * <ul>
 *   <li>Order outbound (D4 · D6): every outbound row of the same order on that day moves; the undecided lots they used
 *       are re-split and the product's debts rewritten ({@link LotLedgerService#afterOutbound}). Cost snapshots, sales
 *       and settlements are not recalculated (D10).</li>
 *   <li>Decided purchase row (D12 · D25): its purchase record's purchase day moves too, and a 「영수증 나중에」 receipt of
 *       it alone. A receipt with its photo blocks the move.</li>
 *   <li>Undecided intake row (D13 · D25): its record, its shares, the minus rows of those shares and their
 *       「영수증 나중에」 receipts move too. A share on a receipt with its photo blocks the move.</li>
 *   <li>Every other row (D15): only its day.</li>
 *   <li>A purchase day moved → the registered price follows a new latest mover only (D14 · D28).</li>
 * </ul>
 *
 * <p>Limits (D5 · D27 · D33 · D34): not after today (KST) · an order outbound not before its order day · an outgoing
 * row not before the day of the lot it came from · a lot not after the first day a row went out of it.
 */
public interface StockDateEditService {

    /**
     * Move one row of the daily view (D9) to {@code newMovedOn} — an order outbound row takes every row of that order
     * on its day (D4). The same day as now = nothing happens.
     *
     * <p>400 (checked in this order, before any write): no row 「고칠 줄을 고르세요.」 · no day 「고칠 내용을 보내세요.」 ·
     * a purchase on a receipt with its photo 「영수증에 묶인 입고는 날짜를 옮길 수 없습니다.」 · an undecided row with a share on
     * such a receipt 「영수증에 묶인 몫이 있어 날짜를 옮길 수 없습니다.」 · a 「영수증 나중에」 receipt holding other purchases
     * 「같은 영수증에 다른 입고가 함께 있어 날짜를 옮길 수 없습니다.」 · after today 「오늘 이후 날짜로는 옮길 수 없습니다.」 · before
     * the order day 「주문일(○월 ○일)보다 앞으로 옮길 수 없습니다.」 · before the lot's day
     * 「○월 ○일 입고에서 나간 줄이라 그보다 앞으로 옮길 수 없습니다.」 · after the first outgoing day
     * 「이 입고에서 ○월 ○일에 나간 기록이 있어 그보다 뒤로 옮길 수 없습니다.」. Missing row → 404.
     */
    void moveRow(Long movementId, LocalDate newMovedOn);

    /**
     * The receipt header edit changed the purchase day (D26): every stock row of {@code lines} that is not on
     * {@code newDay} moves there and the lines take that purchase day. A share keeps its undecided row (D30). Call it
     * before anything of the edit is written.
     *
     * <p>400: after today 「구매일은 오늘 이후로 정할 수 없습니다.」 (D32) · a row after the first outgoing day of it → one
     * line per product 「{상품명}: 이 입고에서 ○월 ○일에 나간 기록이 있어 그보다 뒤로 옮길 수 없습니다.」, joined by a line
     * break (D31).
     *
     * @param lines the receipt's purchase records, record order, with their product
     */
    void moveReceiptDay(List<PurchaseRecord> lines, LocalDate newDay);
}
