package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.SalesLineGroup;
import com.pms.dto.response.SalesLineView;
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
 * 매출 집계 쿼리 (FEATURE_2609_30 / PLAN D14 · 03 Step 1).
 *
 * <p>🔴 <b>이 규칙들은 SQL 안에 있어서 목으로는 검증되지 않는다</b> — 유효수량 정의(취소는 빼고 환불대기는
 * 안 뺀다), 할인의 유효수량 비례 안분, 그리고 채널 옵션이 없는 라인을 <b>버리지 않는</b> left join 이
 * 그것이다. 셋 중 하나라도 조용히 틀리면 화면의 합계가 어긋나고, 합계가 어긋나면 리포트를 아무도 믿지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class OrderLineSalesAggregationTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 9, 10, 10, 0);

    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private TestEntityManager em;

    private Seller seller;
    private MarketplaceAccount account;
    private ProductListingOption option;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
        option = option("옵션A", "양말세트");
    }

    /** 🔴 D14: 확정 취소는 빼고, 환불대기(holdQty)는 <b>빼지 않고</b> 따로 싣는다. */
    @Test
    void netQtySubtractsCancelButNotHold() {
        line(option, 10, 3, 2, "1000", "0", null);
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement()
                .extracting(SalesLineGroup::netQty, SalesLineGroup::holdQty)
                .containsExactly(7L, 2L);
    }

    /** 할인은 유효수량 비례로 안분한다 — 절반이 취소되면 절반만 그 기간의 할인이다. */
    @Test
    void discountIsProratedByNetQty() {
        line(option, 10, 5, 0, "1000", "1000", null);
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement()
                .extracting(SalesLineGroup::discount)
                .satisfies(discount -> assertThat((BigDecimal) discount).isEqualByComparingTo("500"));
    }

    /**
     * 🔴 할인을 매출에서 빼지 않는다. {@code discountAmount} 는 부담 주체가 섞여 있고 그것을 확정하는 것은
     * 정산 API 다 — 매출액과 할인액을 각각 내려보내고 해석은 대사(02)가 한다.
     */
    @Test
    void discountIsNotSubtractedFromGrossSales() {
        line(option, 10, 0, 0, "1000", "1000", null);
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement()
                .extracting(SalesLineGroup::grossSales)
                .satisfies(gross -> assertThat((BigDecimal) gross).isEqualByComparingTo("10000"));
    }

    /**
     * 채널 옵션 연결이 없는 라인(백필 누락·WING 수정분)은 <b>사라지지 않고</b> 자기 그룹으로 남는다.
     * inner join 으로 바꾸면 이 행이 통째로 증발하고 합계가 조용히 줄어든다.
     */
    @Test
    void unmappedLinesSurviveAsTheirOwnGroup() {
        line(option, 2, 0, 0, "1000", "0", null);
        line(null, 3, 0, 0, "2000", "0", null);
        em.flush();
        em.clear();

        List<SalesLineGroup> groups = aggregate();

        assertThat(groups).hasSize(2);
        assertThat(groups).filteredOn(group -> group.listingOptionId() == null)
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.masterProductId()).isNull();
                    assertThat(group.grossSales()).isEqualByComparingTo("6000");
                });
    }

    /**
     * 원가 스냅샷 결손은 <b>유효수량이 남은</b> 라인만 센다. 전량 취소된 라인은 애초에 나가지 않아 스냅샷이
     * 없는 것이 정상인데, 그것까지 세면 취소 한 건에 그 그룹의 순이익이 통째로 {@code null} 이 된다.
     */
    @Test
    void missingCostIsCountedOnlyForLinesWithLiveQty() {
        line(option, 2, 2, 0, "1000", "0", null);          // fully cancelled, never shipped
        line(option, 2, 0, 0, "1000", "0", "3000");        // shipped, snapshot present
        em.flush();
        em.clear();

        assertThat(aggregate()).singleElement()
                .extracting(SalesLineGroup::missingCostLines).isEqualTo(0L);
    }

    /** 기간 밖 주문은 집계되지 않는다(상한은 다음 날 00:00 배타). */
    @Test
    void ordersOutsidePeriodAreExcluded() {
        line(option, 1, 0, 0, "1000", "0", null, LocalDateTime.of(2026, 10, 1, 0, 0));
        em.flush();
        em.clear();

        assertThat(aggregate()).isEmpty();
    }

    /**
     * 🔴 판매 내역 목록의 금액은 집계와 <b>같은 식</b>이어야 한다 — 어긋나면 이 목록의 합이 화면 위쪽
     * 채널 합계와 달라지고, 둘 중 어느 쪽이 맞는지 화면을 보는 사람이 알 수 없게 된다.
     *
     * <p>마스터에 연결되지 않은 라인도 <b>목록에 남는다</b>(상품명만 null) — 빼면 합계가 어긋난다.
     */
    @Test
    void salesLinesCarryTheSameAmountsAndKeepUnmappedLines() {
        line(option, 10, 3, 2, "1000", "500", null);   // netQty 7, gross 7000, discount 350
        line(null, 2, 0, 0, "2000", "0", null);        // 채널 옵션 미연결

        em.flush();
        em.clear();

        List<SalesLineView> lines = orderLineRepository.findSalesLines(FROM, TO_EXCLUSIVE, account.getId());

        assertThat(lines).hasSize(2);
        assertThat(lines).filteredOn(view -> view.masterProductName() != null)
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.netQty()).isEqualTo(7L);
                    assertThat(view.holdQty()).isEqualTo(2L);
                    assertThat(view.grossSales()).isEqualByComparingTo("7000");
                    assertThat(view.discount()).isEqualByComparingTo("350");
                });
        assertThat(lines).filteredOn(view -> view.masterProductName() == null)
                .singleElement()
                .satisfies(view -> assertThat(view.grossSales()).isEqualByComparingTo("4000"));
    }

    /** 🔴 다른 채널의 라인은 섞이지 않는다 — 한 채널을 들여다보는 목록이다. */
    @Test
    void salesLinesAreScopedToOneAccount() {
        line(option, 1, 0, 0, "1000", "0", null);
        em.flush();
        em.clear();

        assertThat(orderLineRepository.findSalesLines(FROM, TO_EXCLUSIVE, account.getId() + 999L)).isEmpty();
    }

    // ------------------------------------------------------------- fixtures

    private List<SalesLineGroup> aggregate() {
        return orderLineRepository.aggregateSales(FROM, TO_EXCLUSIVE, null);
    }

    private ProductListingOption option(String optionName, String masterName) {
        MasterProduct master = em.persist(MasterProduct.builder()
                .name(masterName).active(true).build());
        MasterProductOption masterOption = em.persist(MasterProductOption.builder()
                .masterProduct(master).name(optionName).build());
        ProductListing cell = em.persist(ProductListing.builder()
                .seller(seller).platform(Platform.COUPANG).name(masterName).masterProduct(master).build());
        return em.persist(ProductListingOption.builder()
                .productListing(cell).masterProductOption(masterOption)
                .optionName(optionName).sellingPrice(new BigDecimal("10000")).build());
    }

    private void line(ProductListingOption listingOption, int orderQty, int cancelQty, int holdQty,
                      String unitPrice, String discount, String costAmount) {
        line(listingOption, orderQty, cancelQty, holdQty, unitPrice, discount, costAmount, ORDERED_AT);
    }

    private void line(ProductListingOption listingOption, int orderQty, int cancelQty, int holdQty,
                      String unitPrice, String discount, String costAmount, LocalDateTime orderedAt) {
        Order order = em.persist(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O-" + System.nanoTime()).orderedAt(orderedAt).build());
        em.persist(OrderLine.builder()
                .order(order).productListingOption(listingOption).status(OrderStatus.PAID)
                .itemName("양말세트").orderQty(orderQty).cancelQty(cancelQty).holdQty(holdQty)
                .unitPrice(new BigDecimal(unitPrice))
                .lineAmount(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(orderQty)))
                .discountAmount(new BigDecimal(discount))
                .costAmount(costAmount == null ? null : new BigDecimal(costAmount))
                .build());
    }
}
