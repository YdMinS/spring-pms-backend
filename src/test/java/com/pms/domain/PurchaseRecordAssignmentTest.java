package com.pms.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receipt assignment amounts (FEATURE_2610_19 D130 · D135 ① round 32 · PLAN R4) — the factories of {@link PurchaseRecord}.
 */
class PurchaseRecordAssignmentTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private final Product product = Product.builder().id(1L).productName("양말").price(new BigDecimal("900")).build();
    private final Seller payer = Seller.builder().id(2L).sellerName("A상사").build();
    private final StockMovement first = StockMovement.builder().id(10L).build();
    private final StockMovement second = StockMovement.builder().id(11L).build();

    /** R4: one unit price for every part, the last part takes the rounding remainder — the parts add up to the total. */
    @Test
    void assignedParts_totalSplitsWithOneUnitPrice() {
        List<PurchaseRecord> parts = PurchaseRecord.assignedParts(product, payer, DAY, new BigDecimal("1000"), null,
                null, null, List.of(Map.entry(first, 2), Map.entry(second, 1)));

        assertThat(parts).extracting(PurchaseRecord::getUnitPrice)
                .containsExactly(new BigDecimal("333.3333"), new BigDecimal("333.3333"));
        assertThat(parts).extracting(PurchaseRecord::getTotalAmount)
                .containsExactly(new BigDecimal("666.67"), new BigDecimal("333.33"));
        assertThat(parts).extracting(r -> r.getSourceMovement().getId()).containsExactly(10L, 11L);
        assertThat(parts).allSatisfy(r -> assertThat(r.getReflectToBasePrice()).isTrue());
    }

    /** D135 ① ②: no amount typed → the registered price, base price not moved. */
    @Test
    void assignedParts_blankAmountUsesRegisteredPrice() {
        List<PurchaseRecord> parts = PurchaseRecord.assignedParts(product, payer, DAY, null, null, true, null,
                List.of(Map.entry(first, 2)));

        assertThat(parts).singleElement().satisfies(r -> {
            assertThat(r.getUnitPrice()).isEqualByComparingTo("900");
            assertThat(r.getTotalAmount()).isEqualByComparingTo("1800");
            assertThat(r.getReflectToBasePrice()).isFalse();
        });
    }

    /** D135 ①: no amount typed and no registered price → amount unknown, the reflect choice kept as typed (same as {@code of}). */
    @Test
    void assignedParts_blankAmountWithoutRegisteredPriceKeepsReflect() {
        Product noPrice = Product.builder().id(3L).productName("장갑").build();
        List<PurchaseRecord> parts = PurchaseRecord.assignedParts(noPrice, payer, DAY, null, null, true, null,
                List.of(Map.entry(first, 2)));

        assertThat(parts).singleElement().satisfies(r -> {
            assertThat(r.getUnitPrice()).isNull();
            assertThat(r.getTotalAmount()).isNull();
            assertThat(r.getReflectToBasePrice()).isTrue();
        });
    }

    /** D130 ② · D135 ③: the minus row keeps the undecided unit price. */
    @Test
    void assignmentMinus_keepsUnitPrice() {
        PurchaseRecord undecided = PurchaseRecord.builder().product(product).purchasedOn(DAY).quantity(3)
                .unitPrice(new BigDecimal("900.0000")).reflectToBasePrice(false).build();

        PurchaseRecord minus = PurchaseRecord.assignmentMinus(undecided, 2);

        assertThat(minus.getQuantity()).isEqualTo(-2);
        assertThat(minus.getTotalAmount()).isEqualByComparingTo("-1800.00");
        assertThat(minus.getSeller()).isNull();
    }
}
