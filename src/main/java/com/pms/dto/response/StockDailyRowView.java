package com.pms.dto.response;

import com.pms.domain.PurchaseRecord;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.util.List;

/**
 * One ledger row of 「그날 입출고 내역」 (FEATURE_2610_19 D119 · D123 · D124 · D128 · D130).
 *
 * @param imageUrl      the product's representative photo ({@code product.image_url}) as stored; null = none
 *                      (FEATURE_2611_02 D8)
 * @param sellerId      the seller of {@code sellerName}; null when {@code sellerName} is null (FEATURE_2611_16 D11)
 * @param sellerName    outbound = the order seller (D124); undecided intake = null; other rows = the row's seller
 * @param owners        undecided intake only: the assigned owners as 「{seller} {qty}」 (D130)
 * @param undecidedLeft undecided intake only: what no receipt took yet (D121 ⑤); null for other rows
 * @param orderNumber   outbound only
 * @param tags          outbound only: 「{seller} 재고 사용」 · 「영수증 미등록 재고 사용」 (D124 · D128)
 * @param amount        purchase-price amount (D123); undecided intake = its shares' amounts + what is left × the
 *                      undecided unit price (FEATURE_2611_16 D8); null = 「미기입」. Never a selling price (D122)
 * @param sellerChangeable a decided incoming row without a receipt — 「판매자 바꾸기」 may move it (FEATURE_2611_01 D15)
 * @param recordId      the purchase record 「매입가 넣기」 retypes (FEATURE_2611_16 D1 · D17); null = not a target
 * @param receiptKind   that record's receipt kind; null when {@code recordId} is null (D13 · D18)
 * @param receiptId     that record's receipt; null = none (D13)
 * @param shares        undecided intake only: every assigned share, record order — each one is a 「매입가 넣기」
 *                      target and the 「영수증 없음」 ones are 「판매자 바꾸기」 targets (D8 · FEATURE_2611_01 D15)
 */
public record StockDailyRowView(
        Long movementId,
        StockMovementType movementType,
        StockReason reason,
        Long productId,
        String productName,
        String imageUrl,
        int quantity,
        Long sellerId,
        String sellerName,
        List<String> owners,
        Integer undecidedLeft,
        String orderNumber,
        List<String> tags,
        BigDecimal amount,
        boolean sellerChangeable,
        Long recordId,
        PurchaseRecord.ReceiptKind receiptKind,
        Long receiptId,
        List<Share> shares
) {
    /**
     * One share of an undecided intake row (FEATURE_2611_16 D8): its purchase record, owner, quantity, amount (the
     * record's total; null = 「미기입」) and receipt kind.
     */
    public record Share(Long recordId, Long sellerId, String sellerName, int quantity, BigDecimal amount,
                        PurchaseRecord.ReceiptKind receiptKind, Long receiptId, boolean sellerChangeable) {}
}
