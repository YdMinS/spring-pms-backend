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
 * Receipt check-in, receipt header edit and receipt reads (FEATURE_2610_18 D28 · D45 · D76 · D87~D89 · D93 · D94 ·
 * D100 · D101).
 *
 * <p>🔴 A receipt line is one check-in: {@link PurchaseListService#addPurchase(com.pms.dto.request.PurchaseRecordRequest,
 * com.pms.domain.PurchaseReceipt)} owns the amount, base price and stock rules — {@link #create} never writes
 * purchase_record or stock_movement by itself. {@link #update} rewrites only the seller and purchase day of the
 * receipt's lines and the seller of their stock rows (D100 · D101) — never an amount, a quantity or a base price.</p>
 *
 * <p>🔴 No debt is written for a receipt (D88) — debts come from shipping only (FEATURE_2610_19).</p>
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
     * same transaction. A changed payer seller also moves the stock rows of those lines (D101). Missing receipt →
     * 404 · no card and no payer seller → 400 「결제 판매자를 선택하세요.」 · another hidden card → 404.
     */
    ReceiptDetailView update(Long receiptId, ReceiptUpdateRequest request);

    /** Receipt list: purchase day in [from, to], payer seller (null = all), product name part (blank = any). */
    List<ReceiptSummaryView> search(LocalDate from, LocalDate to, Long sellerId, String productName);

    /** One receipt with its photo and lines. Missing → 404. */
    ReceiptDetailView get(Long receiptId);
}
