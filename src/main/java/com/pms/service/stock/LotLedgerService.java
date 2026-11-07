package com.pms.service.stock;

import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.dto.response.StockBalanceView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Lots (incoming rows) and the debts that come from using another seller's lot (FEATURE_2610_19).
 *
 * <p>🔴 <b>The only place</b> that decides which lot a row uses and how much one seller owes another. Callers write
 * the ledger rows this service returns; they never pick a lot themselves.
 *
 * <ul>
 *   <li>Outbound (D32 ~ D35 · D44 · D48 · D116 ① ⑦): own lots first oldest first, then everybody else's dearest
 *       first, ties = decided before undecided, then seller rank (last month's gross sales), then oldest.</li>
 *   <li>Disposal / adjust − (D50 · D116 ③ · D131): the row seller's own lots only, oldest first — never an undecided
 *       lot, not even an assigned share of one.</li>
 *   <li>Return (D139 · D140 · D142): back into the order line's own lots dearest first; everything else becomes a
 *       new lot of the order seller.</li>
 *   <li>Undecided lots (D130 · D131): owners are the assigned purchase records; the split of the outgoing rows is a
 *       calculation result rewritten by {@link #resplit}.</li>
 *   <li>Debts (D33 · D132 · D136 · D137): a calculation result rewritten per product by {@link #rebuildDebts}.</li>
 * </ul>
 *
 * <p>⚠️ Stock link off (D65): every allocation returns one slice without a lot — the ledger keeps today's rows and
 * no debt arises.
 *
 * <p>❌ Never edit a ledger row's quantity or source (D125). The only columns that change later are the seller
 * ({@link #moveOwner}, D149) and the unit price, which is typed in two places: on the debt screen (D141 · D143)
 * and again by 「일별 입출고 내역」 [매입가 넣기] (FEATURE_2611_16 D6).
 */
public interface LotLedgerService {

    /** Lots for an outbound of {@code quantity} pieces of {@code product} for the order seller. */
    List<LotSlice> allocateOutbound(Product product, Seller orderSeller, int quantity, LocalDate movedOn);

    /** Lots for a disposal or adjust − of {@code quantity} pieces from {@code seller}'s own lots (D50). */
    List<LotSlice> allocateOwn(Product product, Seller seller, int quantity);

    /** Where a matched return goes (D139 · D142). The order seller owns every slice. */
    List<LotSlice> allocateReturn(OrderLine line, Product product, Seller orderSeller, int quantity);

    /**
     * After outbound rows were saved: re-split the undecided lots they used (D131), rewrite the product's debts
     * (D132) and return the notice lines of the debts those rows created (D49 · D137), per row id.
     */
    Map<Long, List<String>> afterOutbound(Long productId, List<StockMovement> savedRows);

    /** Rewrite the split of one undecided lot's outgoing rows over its owners (D131). */
    void resplit(StockMovement undecidedLot);

    /** Delete and rewrite every 「출고 차용」 row of one product (D111 · D132). Cash settlements are not touched. */
    void rebuildDebts(Long productId);

    /**
     * Purchase-price amount of outgoing rows (D123 ② · D136 ③), per row id: Σ pieces × (the lot's or share's unit
     * price, else that owner's dearest priced lot just before the row). null = 「미기입」 — a piece had no price, an
     * undecided piece had none, or the row used no lot (D48 ③).
     */
    Map<Long, BigDecimal> outgoingAmounts(Long productId, Collection<Long> outRowIds);

    /**
     * Purchase-price value of 「재고 조회」 lines (FEATURE_2611_02 D23 · D30 ~ D32 · D25), per given line: the pieces of
     * that product still left whose owner is the line's seller (null = the undecided part) × their unit price — a
     * decided owner's piece without a price takes that owner's dearest priced piece left now, an undecided piece takes
     * none. Σ is scaled to the line's {@code onHand} when the two counts differ, then rounded once to 2 decimals
     * (HALF_UP). onHand 0 = 0 · onHand < 0, a piece without a price or no piece left = null (「미기입」). One ledger load
     * per product (D33).
     */
    Map<StockBalanceView, BigDecimal> stockAmounts(Collection<StockBalanceView> lines);

    /**
     * A receipt's payer changed (D101 · D149) or 「판매자 바꾸기」 moved rows and 「영수증 없음」 shares (FEATURE_2611_01
     * D7): the given lots and every row that uses them take the new owner, the assigned shares re-split their lots, and
     * the debts of every touched product are rewritten (D132).
     */
    void moveOwner(Collection<StockMovement> lots, Collection<PurchaseRecord> shares, Seller newOwner);
}
