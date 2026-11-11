package com.pms.service.stock;

import com.pms.dto.request.StockFillRequest;
import com.pms.dto.response.FillCandidateView;
import com.pms.dto.response.FillLotView;

import java.util.Collection;
import java.util.List;

/**
 * An order outbound row that left without a lot, filled later from a lot (FEATURE_2611_22). ADMIN only through
 * {@code StockFillController}.
 *
 * <p>🔴 <b>The only place</b> that writes a source onto a saved row (D9) and splits a saved row's quantity (D8). A row
 * filled in part becomes the filled row (the original row, keeping its record order and day corrections) and a new
 * row still without a lot (same product, order line, order seller, day).
 *
 * <p>❌ Never automatic (D3 · {@code StockMovement} 「Nothing automatic writes here」): only a person's [채우기] fills.
 * Stock link off = nothing to fill (D11 · D35). Only order outbound rows written at or after the last time the stock
 * link was turned on are filled (D11 · D16 · D28); a lot that is a return is never used (D18).
 */
public interface StockFillService {

    /**
     * The 「채울까요?」 window after an intake save (D19 · D23 · D30 · D31 · D32): per product of {@code lotIds} (first
     * appearance order) with at least one row to fill, its saved lots in that order, each with every row to fill in D17
     * order and the pieces checked at first. Ids that are not a lot that may fill, or have nothing left, are skipped;
     * stock link off = empty.
     */
    List<FillCandidateView> candidates(List<Long> lotIds);

    /** 「일별 입출고 내역」 [입고로 채우기] (D15): the lots of that row's product with a piece left, day then record order. */
    List<FillLotView> lots(Long movementId);

    /**
     * [채우기] (D5 · D6 · D8 · D9 · D10 · D13 · D15 · D23 · D32): every check first (the first refusal is the 400
     * message), then each line in order, then the undecided lots used are re-split, the debts rewritten and the order
     * lines costed again. No notice is returned (D13).
     */
    void fill(StockFillRequest request);

    /**
     * The price of these lots (or of a share of them) was typed or filled (D22 · D25 · D37): every order line with a
     * row filled later from one of them is costed again from scratch ({@code CostBasisResolver.refill}).
     */
    void recostFilled(Collection<Long> lotIds);
}
