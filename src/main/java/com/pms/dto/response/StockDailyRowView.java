package com.pms.dto.response;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.util.List;

/**
 * One ledger row of 「그날 입출고 내역」 (FEATURE_2610_19 D119 · D123 · D124 · D128 · D130).
 *
 * @param imageUrl      the product's representative photo ({@code product.image_url}) as stored; null = none
 *                      (FEATURE_2611_02 D8)
 * @param sellerName    outbound = the order seller (D124); undecided intake = null; other rows = the row's seller
 * @param owners        undecided intake only: the assigned owners as 「{seller} {qty}」 (D130)
 * @param undecidedLeft undecided intake only: what no receipt took yet (D121 ⑤); null for other rows
 * @param orderNumber   outbound only
 * @param tags          outbound only: 「{seller} 재고 사용」 · 「영수증 미등록 재고 사용」 (D124 · D128)
 * @param amount        purchase-price amount (D123); null = 「미기입」. Never a selling price (D122)
 * @param sellerChangeable a decided incoming row without a receipt — 「판매자 바꾸기」 may move it (FEATURE_2611_01 D15)
 * @param noReceiptShares  undecided intake only: its 「영수증 없음」 shares, each one 「판매자 바꾸기」 target (D15)
 */
public record StockDailyRowView(
        Long movementId,
        StockMovementType movementType,
        StockReason reason,
        Long productId,
        String productName,
        String imageUrl,
        int quantity,
        String sellerName,
        List<String> owners,
        Integer undecidedLeft,
        String orderNumber,
        List<String> tags,
        BigDecimal amount,
        boolean sellerChangeable,
        List<NoReceiptShare> noReceiptShares
) {
    /** One 「영수증 없음」 share: its purchase record, owner and quantity. */
    public record NoReceiptShare(Long recordId, String sellerName, int quantity) {}
}
