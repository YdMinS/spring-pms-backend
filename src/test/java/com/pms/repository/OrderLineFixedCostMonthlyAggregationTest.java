package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.MonthlyChannelSales;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고정비 판정용 월별 매출 집계 (FEATURE_2609_33 / PLAN 2609_33 D4-1 · D11 · D11-1).
 *
 * <p>🔴 <b>이 쿼리의 유일한 방어선</b>이다. 서비스 테스트는 리포지토리를 목으로 대체하므로 금액식이
 * 틀려도 전부 그린이다 — 달 그룹핑(주문일 축)과 "할인 후" 금액식은 여기서만 검증된다.
 *
 * <p>판정 매출이 조용히 틀리면 임계 근처에서 부과 여부가 뒤집혀 순이익이 채널당 매달 55,000 씩 어긋난다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class OrderLineFixedCostMonthlyAggregationTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2026, 10, 1, 0, 0);

    private static final LocalDateTime AUG_31 = LocalDateTime.of(2026, 8, 31, 23, 0);
    private static final LocalDateTime SEP_1 = LocalDateTime.of(2026, 9, 1, 1, 0);

    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private TestEntityManager em;

    private Seller seller;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    /** 🔴 D11-1: 날짜 축은 주문일이다 — 8/31 과 9/1 은 다른 달로 갈린다(경계에서 부과가 뒤집힌다). */
    @Test
    void testGroupsByOrderedMonth() {
        line(account, 1, 0, "10000", "0", AUG_31);
        line(account, 1, 0, "20000", "0", SEP_1);
        em.flush();
        em.clear();

        List<MonthlyChannelSales> rows = aggregate();

        assertThat(rows).hasSize(2)
                .extracting(MonthlyChannelSales::year, MonthlyChannelSales::month)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(2026, 8),
                        org.assertj.core.api.Assertions.tuple(2026, 9));
    }

    /**
     * 🔴 D11: 판정 금액은 <b>할인 후</b>다. 할인 전으로 판정하면 쿠폰이 걸린 채널이 부과되지 않은 달을
     * 부과로 판정한다 — {@code aggregateSales} 의 {@code grossSales − discount} 와 같은 값이어야 한다.
     */
    @Test
    void testNetSalesIsAfterDiscount() {
        // unitPrice 10,000 × 10 = 100,000 − discountAmount 20,000(orderQty 10 전량 유효) = 80,000
        line(account, 10, 0, "10000", "20000", SEP_1);
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement()
                .satisfies(row -> assertThat(row.netSales()).isEqualByComparingTo("80000"));
    }

    /** 계정마다 따로 부과되므로(D12) 두 계정의 같은 달 매출은 합쳐지지 않는다. */
    @Test
    void testSplitsByAccount() {
        MarketplaceAccount other = MarketplaceAccountFixture.coupangAccount(em, seller, "V2");
        line(account, 1, 0, "10000", "0", SEP_1);
        line(other, 1, 0, "30000", "0", SEP_1);
        em.flush();
        em.clear();

        assertThat(aggregate()).hasSize(2)
                .extracting(MonthlyChannelSales::accountId)
                .containsExactlyInAnyOrder(account.getId(), other.getId());
    }

    // ------------------------------------------------------------- fixtures

    private List<MonthlyChannelSales> aggregate() {
        return orderLineRepository.aggregateMonthlySales(FROM, TO_EXCLUSIVE, null);
    }

    private void line(MarketplaceAccount target, int orderQty, int cancelQty,
                      String unitPrice, String discount, LocalDateTime orderedAt) {
        Order order = em.persist(Order.builder()
                .marketplaceAccount(target).platform(Platform.COUPANG)
                .externalOrderId("O-" + System.nanoTime()).orderedAt(orderedAt).build());
        em.persist(OrderLine.builder()
                .order(order).status(OrderStatus.PAID)
                .itemName("양말세트").orderQty(orderQty).cancelQty(cancelQty).holdQty(0)
                .unitPrice(new BigDecimal(unitPrice))
                .lineAmount(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(orderQty)))
                .discountAmount(new BigDecimal(discount))
                .build());
    }
}
