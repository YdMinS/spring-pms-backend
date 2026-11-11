package com.pms.dto.response;

import com.pms.domain.PurchaseRecord;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One ledger row of 「그날 입출고 내역」 (FEATURE_2610_19 D119 · D123 · D124 · D128 · D130).
 *
 * @param imageUrl      the product's representative photo ({@code product.image_url}) as stored; null = none
 *                      (FEATURE_2611_02 D8)
 * @param sellerId      the seller of {@code sellerName}; null when {@code sellerName} is null (FEATURE_2611_16 D11)
 * @param sellerName    outbound = the order seller (D124); undecided intake = null; other rows = the row's seller
 * @param owners        undecided intake only: the assigned owners as 「{seller}」 — the name only, each seller once,
 *                      first-share order (D130 · FEATURE_2611_18 D1 · D3)
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
 * @param unitPrices    the unit prices behind {@code amount}, one per price with its count, first appearance order
 *                      (FEATURE_2611_17 D1 · D16): exactly the parts {@code amount} adds up; null when {@code amount}
 *                      is null (「미기입」)
 * @param priceFromBase a part of {@code amount} has a unit price filled from the registered price — the screen shows
 *                      「미기입」 next to the value (FEATURE_2611_17 D22)
 * @param openingPriceEditable an opening-stock or 「기타」 intake row the row popup may price (FEATURE_2611_17 D5 · D17 ·
 *                      FEATURE_2611_22 D38)
 * @param sourceMovementId outgoing rows only: the lot it came from — 「○월 ○일 입고로 이동」 (FEATURE_2611_21 D18 · D22);
 *                      null = no lot (stock link off, no stock left) or not an outgoing row
 * @param sourceMovedOn the day of that lot; null with {@code sourceMovementId}
 * @param sourcePriceEditable that lot has a value to fix — a decided purchase, opening stock or 「기타」 intake, or an
 *                      undecided lot of
 *                      which this row used a registered share (D20); false without a lot
 * @param dateEdits     the day corrections of this row, newest first (D7 · D24); empty = never corrected
 * @param fillable      an order outbound row without a lot that [입고로 채우기] may fill — stock link on and the row
 *                      written at or after the last time it was turned on (FEATURE_2611_22 D3 ② · D11 · D16 · D28)
 * @param filledLater   the row got its lot later — 「{sourceMovedOn} 입고로 나중에 채움」 (FEATURE_2611_22 D9)
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
        List<Share> shares,
        List<UnitCount> unitPrices,
        boolean priceFromBase,
        boolean openingPriceEditable,
        Long sourceMovementId,
        LocalDate sourceMovedOn,
        boolean sourcePriceEditable,
        List<DateEdit> dateEdits,
        boolean fillable,
        boolean filledLater
) {
    /** One day correction of the row (FEATURE_2611_21 D24): when (KST wall clock), who (login email), from → to. */
    public record DateEdit(LocalDateTime editedAt, String editedBy, LocalDate oldMovedOn, LocalDate newMovedOn) {}

    /**
     * One share of an undecided intake row (FEATURE_2611_16 D8): its purchase record, owner, quantity, amount (the
     * record's total; null = 「미기입」) and receipt kind, plus its unit price (null = 「미기입」) and the 「미기입」 mark of
     * a unit price filled from the registered price (FEATURE_2611_17 D2 · D22).
     */
    public record Share(Long recordId, Long sellerId, String sellerName, int quantity, BigDecimal amount,
                        PurchaseRecord.ReceiptKind receiptKind, Long receiptId, boolean sellerChangeable,
                        BigDecimal unitPrice, boolean priceFromBase) {}

    /** One unit price and how many pieces of the row carry it (FEATURE_2611_17 D16). */
    public record UnitCount(BigDecimal unitPrice, int quantity) {

        /** D16: the same unit price (compared by value, not scale) adds up its counts; first appearance order stays. */
        public static List<UnitCount> merge(List<UnitCount> parts) {
            List<UnitCount> merged = new ArrayList<>();
            for (UnitCount part : parts) {
                int index = -1;
                for (int i = 0; i < merged.size(); i++) {
                    if (merged.get(i).unitPrice().compareTo(part.unitPrice()) == 0) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    merged.add(part);
                } else {
                    UnitCount same = merged.get(index);
                    merged.set(index, new UnitCount(same.unitPrice(), same.quantity() + part.quantity()));
                }
            }
            return List.copyOf(merged);
        }

        /** Σ unit × count, rounded once to 2 decimals (HALF_UP) — an outgoing row's amount (D123 ② · D136 ③). */
        public static BigDecimal amountOf(List<UnitCount> parts) {
            return parts.stream().map(p -> p.unitPrice().multiply(BigDecimal.valueOf(p.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
