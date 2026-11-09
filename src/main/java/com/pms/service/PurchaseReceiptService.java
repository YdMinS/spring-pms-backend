package com.pms.service;

import com.pms.dto.request.DailyRowEditRequest;
import com.pms.dto.request.PurchasePriceRequest;
import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.request.ReceiptlessAssignRequest;
import com.pms.dto.request.SellerChangeRequest;
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.ReceiptDetailView;
import com.pms.dto.response.ReceiptSummaryView;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Receipt check-in, receipt header edit, receipt assignment and receipt reads (FEATURE_2610_18 D28 · D45 · D76 ·
 * D87~D89 · D93 · D94 · D100 · D101 · FEATURE_2610_19 D118 · D121), and the daily view's seller without a receipt
 * (FEATURE_2611_01 D2 · D4 · D7).
 *
 * <p>🔴 A {@link #create} line is one check-in: {@link PurchaseListService#addPurchase(com.pms.dto.request.PurchaseRecordRequest,
 * com.pms.domain.PurchaseReceipt)} owns the amount, base price and stock rules — {@link #create} never writes
 * purchase_record or stock_movement by itself. An {@link #assign} line writes no stock row (FEATURE_2610_19 D130)
 * and takes its purchase records from the {@link com.pms.domain.PurchaseRecord} factories (D135 ① round 32).
 * {@link #update} changes only the seller and purchase day of the receipt's lines and the seller and day of the stock
 * rows they reach (D100 · D101 · FEATURE_2610_19 D149 · FEATURE_2611_21 D26) — never a purchase amount or a quantity;
 * a new purchase day re-decides the base price (FEATURE_2611_21 D14 · D28).</p>
 *
 * <p>🔴 A receipt never adds a debt of its own (D88) — borrowed-goods debts come from outbound rows only
 * (FEATURE_2610_19 D33). {@link #assign}, {@link #assignWithoutReceipt}, {@link #update}, {@link #changeSeller},
 * {@link #retypePrices} and {@link #editDailyRow} only recompute the split shares and the outbound debts of the
 * products they touched (D131 · D132).</p>
 *
 * @see com.pms.controller.PurchaseReceiptController
 */
public interface PurchaseReceiptService {

    /**
     * Save the photo and the receipt, then one check-in per line, all owned by the payer seller (D89).
     * Missing photo → 400 「영수증 사진을 올리세요.」 · no card and no payer seller → 400 「결제 판매자를 선택하세요.」.
     */
    ReceiptCreateResult create(ReceiptCreateRequest request, MultipartFile photo);

    /**
     * Receipt header edit (D100): purchase day, card, payer seller — every line takes the new seller and day in the
     * same transaction. A changed payer seller also moves the stock rows of those lines (D101) and every row that used
     * them, and rewrites the debts (FEATURE_2610_19 D149 · D132). A changed purchase day also moves the day of the stock
     * rows of those lines ({@link com.pms.service.stock.StockDateEditService#moveReceiptDay}, FEATURE_2611_21 D26 ·
     * D31 · D32 — its 400 texts). Missing receipt → 404 · no card and no payer seller → 400 「결제 판매자를 선택하세요.」 ·
     * another hidden card → 404.
     */
    ReceiptDetailView update(Long receiptId, ReceiptUpdateRequest request);

    /**
     * Receipt assignment on the daily in/out view (FEATURE_2610_19 D118 · D121): the photo and the card/payer like
     * {@link #create}; each line takes its quantity from that day's undecided intake of the product. More than what is
     * still undecided → 400 「남은 영수증 미등록 수량(N개)보다 많습니다.」 · the same product twice → 400.
     *
     * @param movedOn the day on the screen — only that day's undecided intake can be assigned (D121 ⑥)
     */
    ReceiptCreateResult assign(LocalDate movedOn, ReceiptCreateRequest request, MultipartFile photo);

    /**
     * 「영수증 없이 판매자 정하기」 (FEATURE_2611_01 D2 · D4 · D5 · D8 · D12): the {@link #assign} lines without a photo, owned
     * by the picked seller, purchase day = {@code movedOn}. {@code RECEIPT_LATER} → one receipt without a photo
     * (receiptId in the result) · {@code NO_RECEIPT} → no receipt, the records are marked as found stock (receiptId
     * null). No seller → 400 「판매자를 선택하세요.」 · the {@link #assign} quantity checks → 400.
     */
    ReceiptCreateResult assignWithoutReceipt(LocalDate movedOn, ReceiptlessAssignRequest request);

    /**
     * The photo of a 「영수증 나중에」 receipt (FEATURE_2611_01 D4). Missing receipt → 404 · it has a photo → 400
     * 「이미 사진이 있는 영수증입니다.」 · no photo part → 400 「영수증 사진을 올리세요.」.
     */
    ReceiptDetailView attachPhoto(Long receiptId, MultipartFile photo);

    /**
     * 「판매자 바꾸기」 (FEATURE_2611_01 D7 · D15): decided incoming rows without a receipt and 「영수증 없음」 shares take
     * the new seller with the rows that used them, and the debts are rewritten. No seller → 400 · nothing picked →
     * 400 「판매자를 바꿀 줄을 고르세요.」 · a row or share that is not a target → 400 「판매자를 바꿀 수 없는 줄입니다.」 ·
     * missing → 404.
     */
    void changeSeller(SellerChangeRequest request);

    /**
     * 「일별 입출고 내역」 [매입가 넣기] (FEATURE_2611_16 D1 ~ D3 · D5 · D6 · D11 ~ D19). Each line with an amount retypes
     * its purchase record (total or unit) and every stock row of that record; a checked 「상품 기준가에 반영」 moves the
     * registered price only when the record is the product's latest mover, otherwise the record stops reflecting
     * (D3). Shares re-split their lot and the debts of the touched products are rewritten (D6). A blank line keeps
     * its amount (D7).
     *
     * <p>With a photo: only 「영수증 나중에」 lines of one receipt → the photo goes to that receipt (D13) · only lines
     * without a receipt, one seller → a new receipt of that seller, day = {@code movedOn}, card = {@code cardId}
     * (that seller's visible card) or none, and each line leaves found stock (D2 · D12 · D15).</p>
     *
     * <p>400: not a target 「매입가를 넣을 수 없는 줄입니다.」 · the same record twice 「같은 줄을 두 번 넣었습니다.」 · no
     * amount and no photo 「바꿀 금액을 넣거나 영수증 사진을 올리세요.」 · both amounts 「총액과 단가 중 하나만 입력하세요」 ·
     * photo + a receipt with its photo 「사진 있는 영수증 입고가 있어 사진을 올릴 수 없습니다.」 (D18) · photo + any
     * other mix with 「영수증 나중에」 lines 「사진은 같은 영수증의 「영수증 나중에」 입고만 체크했을 때 올릴 수 있습니다.」
     * (D13) · photo + two sellers 「사진을 넣으면 같은 판매자 입고만 묶을 수 있습니다.」 (D11) · another seller's card
     * 「이 입고 판매자의 카드가 아닙니다.」 · a purchase day other than {@code movedOn}
     * 「구매일이 화면 날짜와 다른 입고는 사진으로 묶을 수 없습니다.」. Missing record → 404 · hidden card → 404.</p>
     *
     * @param movedOn the day on the screen — the new receipt's purchase day (D15)
     * @param photo   the receipt photo, or null / empty = amounts only
     * @return the receipt the photo went to; receiptId null without a photo
     */
    ReceiptCreateResult retypePrices(LocalDate movedOn, PurchasePriceRequest request, MultipartFile photo);

    /**
     * The row popup of 「일별 입출고 내역」 (FEATURE_2611_17 D5 · D10 · D17 ~ D23 · D26): one decided incoming row or one
     * share, saved in one transaction — nothing changes when a part is refused. Order: the seller ({@link #changeSeller}
     * rules) → the price and photo of a purchase ({@link #retypePrices} rules, one line) or the price of an opening-stock
     * row (D17: the row's unit price; an empty registered price takes it) → the picked receipt (the purchase becomes one
     * more line of it, D23 · D26) → the row's new day ({@link com.pms.service.stock.StockDateEditService#moveRow} rules,
     * FEATURE_2611_21 D9). receiptId = the receipt the photo went to or the picked receipt, null otherwise.
     *
     * <p>400: no request 「고칠 내용을 보내세요.」 · neither or both of movementId / shareRecordId 「고칠 줄을 고르세요.」 · a
     * row with nothing to edit 「고칠 수 없는 줄입니다.」 · a seller change on a row that is not a 「판매자 바꾸기」 target
     * 「판매자를 바꿀 수 없는 줄입니다.」 · a price on a row without one 「매입가를 넣을 수 없는 줄입니다.」 · a photo or receipt
     * on a row that is not a purchase 「영수증을 붙일 수 없는 줄입니다.」 · both a photo and a receipt
     * 「새 영수증 사진과 고른 영수증을 함께 넣을 수 없습니다.」 · both amounts 「총액과 단가 중 하나만 입력하세요」 · nothing to
     * change 「바꿀 내용이 없습니다.」 · a picked receipt on a row with a receipt 「영수증을 고를 수 없는 줄입니다.」 · a picked
     * receipt without its photo 「사진 있는 영수증만 고를 수 있습니다.」 · a picked receipt or purchase of another day
     * 「화면 날짜의 영수증만 고를 수 있습니다.」 · a picked receipt of another payer than the seller after the save
     * 「고른 영수증의 결제 판매자와 판매자가 다릅니다.」 · a new day on a share 「고칠 수 없는 줄입니다.」 · a new day with a
     * photo or a picked receipt 「영수증에 묶인 입고는 날짜를 옮길 수 없습니다.」 · every 400 of {@link #changeSeller},
     * {@link #retypePrices} and {@code StockDateEditService.moveRow}.
     * Missing row, record or receipt → 404.</p>
     */
    ReceiptCreateResult editDailyRow(LocalDate movedOn, DailyRowEditRequest request, MultipartFile photo);

    /** Receipt list: purchase day in [from, to], payer seller (null = all), product name part (blank = any). */
    List<ReceiptSummaryView> search(LocalDate from, LocalDate to, Long sellerId, String productName);

    /** One receipt with its photo and lines. Missing → 404. */
    ReceiptDetailView get(Long receiptId);
}
