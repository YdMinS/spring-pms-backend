package com.pms.service;

import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.ReceiptDetailView;
import com.pms.dto.response.ReceiptSummaryView;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Receipt check-in, receipt header edit, receipt assignment and receipt reads (FEATURE_2610_18 D28 · D45 · D76 ·
 * D87~D89 · D93 · D94 · D100 · D101 · FEATURE_2610_19 D118 · D121).
 *
 * <p>🔴 A {@link #create} line is one check-in: {@link PurchaseListService#addPurchase(com.pms.dto.request.PurchaseRecordRequest,
 * com.pms.domain.PurchaseReceipt)} owns the amount, base price and stock rules — {@link #create} never writes
 * purchase_record or stock_movement by itself. An {@link #assign} line writes no stock row (FEATURE_2610_19 D130)
 * and takes its purchase records from the {@link com.pms.domain.PurchaseRecord} factories (D135 ① round 32).
 * {@link #update} changes only the seller and purchase day of the receipt's lines and the seller of the stock rows
 * they reach (D100 · D101 · FEATURE_2610_19 D149) — never a purchase amount, a quantity or a base price.</p>
 *
 * <p>🔴 A receipt never adds a debt of its own (D88) — borrowed-goods debts come from outbound rows only
 * (FEATURE_2610_19 D33). {@link #assign} and {@link #update} only recompute the split shares and the outbound debts
 * of the products they touched (D131 · D132).</p>
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
     * them, and rewrites the debts (FEATURE_2610_19 D149 · D132). Missing receipt → 404 · no card and no payer seller →
     * 400 「결제 판매자를 선택하세요.」 · another hidden card → 404.
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

    /** Receipt list: purchase day in [from, to], payer seller (null = all), product name part (blank = any). */
    List<ReceiptSummaryView> search(LocalDate from, LocalDate to, Long sellerId, String productName);

    /** One receipt with its photo and lines. Missing → 404. */
    ReceiptDetailView get(Long receiptId);
}
