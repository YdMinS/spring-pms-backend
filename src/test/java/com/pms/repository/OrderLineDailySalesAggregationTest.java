package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.DailySalesRow;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sales per sale date (FEATURE_2610_29 D22) — the rules inside the SQL: the amount is the {@code aggregateSales}
 * gross (cancel subtracted, hold not, discount not), the order count is per order and skips an order cancelled in
 * full, and the upper bound is exclusive.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class OrderLineDailySalesAggregationTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2026, 9, 11, 0, 0);

    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private TestEntityManager em;

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        Seller seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    /** Two lines of one order count once; the amount is Σ unitPrice × (orderQty − cancelQty), discount kept in. */
    @Test
    void amountIsGrossAndOrdersAreCountedOnce() {
        Order order = order(LocalDateTime.of(2026, 9, 3, 10, 0));
        line(order, 10, 3, 2, "1000", "500");
        line(order, 1, 0, 0, "2000", "0");
        line(order(LocalDateTime.of(2026, 9, 3, 23, 59)), 2, 0, 0, "1500", "0");
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement().satisfies(row -> {
            assertThat(row.day()).isEqualTo(3);
            assertThat(row.grossSales()).isEqualByComparingTo("12000");
            assertThat(row.orderCount()).isEqualTo(2L);
        });
    }

    /** An order cancelled in full adds nothing to the amount, so it is not counted either. */
    @Test
    void orderCancelledInFullIsNotCounted() {
        line(order(LocalDateTime.of(2026, 9, 4, 9, 0)), 2, 2, 0, "1000", "0");
        line(order(LocalDateTime.of(2026, 9, 5, 9, 0)), 1, 0, 0, "3000", "0");
        em.flush();
        em.clear();

        List<DailySalesRow> rows = aggregate();

        assertThat(rows).extracting(DailySalesRow::day).containsExactly(4, 5);
        assertThat(rows.get(0).grossSales()).isEqualByComparingTo("0");
        assertThat(rows.get(0).orderCount()).isZero();
        assertThat(rows.get(1).orderCount()).isEqualTo(1L);
    }

    /** The upper bound is exclusive — an order at the next day's 00:00 is out. */
    @Test
    void ordersOutsidePeriodAreExcluded() {
        line(order(LocalDateTime.of(2026, 9, 11, 0, 0)), 1, 0, 0, "1000", "0");
        em.flush();
        em.clear();

        assertThat(aggregate()).isEmpty();
    }

    private List<DailySalesRow> aggregate() {
        return orderLineRepository.aggregateDailySales(FROM, TO_EXCLUSIVE).stream()
                .sorted(Comparator.comparingInt(DailySalesRow::day))
                .toList();
    }

    private Order order(LocalDateTime orderedAt) {
        return em.persist(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O-" + System.nanoTime()).orderedAt(orderedAt).build());
    }

    private void line(Order order, int orderQty, int cancelQty, int holdQty, String unitPrice, String discount) {
        em.persist(OrderLine.builder()
                .order(order).status(OrderStatus.PAID)
                .itemName("양말세트").orderQty(orderQty).cancelQty(cancelQty).holdQty(holdQty)
                .unitPrice(new BigDecimal(unitPrice))
                .lineAmount(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(orderQty)))
                .discountAmount(new BigDecimal(discount))
                .build());
    }
}
