package com.pms.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「매입가 넣기」 rules of {@link PurchaseRecord} (FEATURE_2611_16 D2 · D3 · D6 · D13 · D18) — the retyped amount, the
 * receipt kind, the receipt line and the latest-mover check shared with the debt screen.
 */
class PurchaseRecordRetypeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    private final Product product = Product.builder().id(1L).productName("양말").price(new BigDecimal("900")).build();
    private final Seller seller = Seller.builder().id(2L).sellerName("A상사").build();

    private PurchaseRecord record(long id, LocalDate day, int quantity) {
        return PurchaseRecord.builder().id(id).product(product).seller(seller).purchasedOn(day).quantity(quantity)
                .unitPrice(new BigDecimal("900.0000")).totalAmount(new BigDecimal("2700.00"))
                .reflectToBasePrice(false).stockLinked(true)
                .priceFilledAt(LocalDateTime.of(2026, 11, 1, 9, 0)).build();
    }

    /** D6: total → unit = total / quantity (4 places); unit → total = unit × quantity (2 places); the debt mark stays. */
    @Test
    void withRetypedAmount_totalOrUnit() {
        PurchaseRecord byTotal = record(10L, DAY, 3).withRetypedAmount(new BigDecimal("1000"), null, true);
        assertThat(byTotal.getUnitPrice()).isEqualTo(new BigDecimal("333.3333"));
        assertThat(byTotal.getTotalAmount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(byTotal.getReflectToBasePrice()).isTrue();
        assertThat(byTotal.getPriceFilledAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));

        PurchaseRecord byUnit = record(10L, DAY, 3).withRetypedAmount(null, new BigDecimal("1234.5"), false);
        assertThat(byUnit.getUnitPrice()).isEqualTo(new BigDecimal("1234.5000"));
        assertThat(byUnit.getTotalAmount()).isEqualTo(new BigDecimal("3703.50"));
        assertThat(byUnit.getQuantity()).isEqualTo(3);

        assertThatThrownBy(() -> record(10L, DAY, 3).withRetypedAmount(BigDecimal.ONE, BigDecimal.ONE, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("총액과 단가 중 하나만 입력하세요");
    }

    /** D13 · D18: no receipt · a receipt without its photo · a receipt with its photo. */
    @Test
    void receiptKind_byReceiptAndPhoto() {
        PurchaseReceipt later = PurchaseReceipt.builder().id(5L).build();
        PurchaseReceipt photo = PurchaseReceipt.builder().id(6L).photoUrl("https://bucket/r.png").build();
        assertThat(record(10L, DAY, 1).receiptKind()).isEqualTo(PurchaseRecord.ReceiptKind.NO_RECEIPT);
        assertThat(record(10L, DAY, 1).toBuilder().receipt(later).build().receiptKind())
                .isEqualTo(PurchaseRecord.ReceiptKind.RECEIPT_LATER);
        assertThat(record(10L, DAY, 1).toBuilder().receipt(photo).build().receiptKind())
                .isEqualTo(PurchaseRecord.ReceiptKind.RECEIPT);
    }

    /** D2: a found-stock purchase bound to a receipt loses the mark. */
    @Test
    void asReceiptLine_clearsFoundStock() {
        PurchaseReceipt receipt = PurchaseReceipt.builder().id(6L).photoUrl("https://bucket/r.png").build();
        PurchaseRecord line = record(10L, DAY, 1).asFoundStock(LocalDateTime.of(2026, 11, 7, 10, 0))
                .asReceiptLine(receipt);
        assertThat(line.getReceipt()).isSameAs(receipt);
        assertThat(line.getFoundStockAt()).isNull();
    }

    /** D3 (FEATURE_2610_19 D144 ①): a later purchase day, or the same day and a later record, is newer. */
    @Test
    void isLatestMover_byDayThenRecordOrder() {
        PurchaseRecord target = record(10L, DAY, 1);
        assertThat(PurchaseRecord.isLatestMover(target, List.of(target, record(9L, DAY, 1)))).isTrue();
        assertThat(PurchaseRecord.isLatestMover(target, List.of(record(11L, DAY, 1)))).isFalse();
        assertThat(PurchaseRecord.isLatestMover(target, List.of(record(4L, DAY.plusDays(1), 1)))).isFalse();
        assertThat(PurchaseRecord.isLatestMover(target, List.of(record(20L, DAY.minusDays(1), 1)))).isTrue();
    }
}
