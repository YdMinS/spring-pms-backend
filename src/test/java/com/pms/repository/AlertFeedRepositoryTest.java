package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.NewOrderAlertRow;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 목록 조회 쿼리 (FEATURE_2609_51 / PLAN D5·D6·D8·D12).
 *
 * <p>🔴 <b>이 규칙들은 SQL 안에 있어서 목으로는 검증되지 않는다</b> — 주문 단위 집계(라인 3개 → 행 1개),
 * 전량취소 제외의 SQL 표현, 기간 하한, 그리고 <b>동률까지 쿼리에서 자르는 커서</b>가 그것이다.
 * 커서가 틀리면 장 경계에서 행이 조용히 사라지고, 사라진 행은 화면에서 영영 안 보인다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class AlertFeedRepositoryTest {

    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 9, 10, 10, 0);
    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime CURSOR_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private OrderClaimRepository orderClaimRepository;
    @Autowired private CustomerInquiryRepository customerInquiryRepository;
    @Autowired private TestEntityManager em;

    private Seller seller;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    /** 🔴 D5: 라인이 몇 개든 알림은 주문 1건이고 {@code itemCount} 가 그 수다. */
    @Test
    void groupsLinesIntoOneRowPerOrder() {
        Order order = order("O-1", ORDERED_AT);
        line(order, 1, 0, 0, "양말세트");
        line(order, 2, 0, 0, "수건세트");
        line(order, 1, 0, 0, "비누세트");
        em.flush();
        em.clear();

        List<NewOrderAlertRow> rows = newOrders(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.externalOrderId()).isEqualTo("O-1");
            assertThat(row.itemCount()).isEqualTo(3L);
            assertThat(row.platform()).isEqualTo(Platform.COUPANG);
            assertThat(row.sellerName()).isEqualTo("셀러A");
            assertThat(row.itemName()).isNotBlank();
        });
    }

    /** 🔴 D6: 전량취소된 주문은 피드·두 카운트 <b>셋 다</b>에서 빠진다 — 이미 취소된 주문을 재촉하지 않는다. */
    @Test
    void excludesFullyCancelledLines() {
        line(order("O-CANCELLED", ORDERED_AT), 2, 2, 0, "양말세트");
        em.flush();
        em.clear();

        assertThat(newOrders(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50))).isEmpty();
        assertThat(orderLineRepository.countPaidOrders(OrderStatus.PAID)).isZero();
        assertThat(orderLineRepository.countNewOrders(OrderStatus.PAID, WINDOW_FROM)).isZero();
    }

    /** 부분취소는 아직 할 일이다 — 남은 수량이 있으면 알림에 남는다. */
    @Test
    void includesPartiallyCancelledLines() {
        line(order("O-PARTIAL", ORDERED_AT), 3, 1, 0, "양말세트");
        em.flush();
        em.clear();

        assertThat(newOrders(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50))).hasSize(1);
        assertThat(orderLineRepository.countNewOrders(OrderStatus.PAID, WINDOW_FROM)).isEqualTo(1);
    }

    /**
     * 🔴 메뉴 배지도 <b>주문 단위</b>다(2026-09-16) — 상품 3개짜리 주문 하나는 1 이다.
     * 상품 수로 세면 같은 일이 메뉴에서는 3, 종 배지에서는 1 로 보인다.
     */
    @Test
    void countsPaidOrdersByOrderNotByLine() {
        Order order = order("O-MULTI", ORDERED_AT);
        line(order, 1, 0, 0, "양말세트");
        line(order, 2, 0, 0, "수건세트");
        line(order, 1, 0, 0, "비누세트");
        em.flush();
        em.clear();

        assertThat(orderLineRepository.countPaidOrders(OrderStatus.PAID)).isEqualTo(1);
        assertThat(orderLineRepository.countNewOrders(OrderStatus.PAID, WINDOW_FROM)).isEqualTo(1);
    }

    /**
     * 🔴 D8: 기간 밖 주문은 피드·{@code countNewOrders} 에서 빠지지만 {@code countPaidOrders} 에는 <b>남는다</b>
     * (메뉴 배지는 "지금 발주처리해야 할 주문 수"라 오래된 것도 세야 맞다). 클레임·문의도 각자 기간 밖이면 빠진다.
     */
    @Test
    void excludesRowsOutsideWindow() {
        line(order("O-OLD", LocalDateTime.of(2026, 8, 1, 10, 0)), 1, 0, 0, "양말세트");
        claim(LocalDateTime.of(2026, 8, 1, 10, 0));
        inquiry(LocalDateTime.of(2026, 8, 1, 10, 0));
        em.flush();
        em.clear();

        assertThat(newOrders(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50))).isEmpty();
        assertThat(orderLineRepository.countNewOrders(OrderStatus.PAID, WINDOW_FROM)).isZero();
        assertThat(orderLineRepository.countPaidOrders(OrderStatus.PAID)).isEqualTo(1);

        assertThat(openClaims(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50))).isEmpty();
        assertThat(orderClaimRepository.countOpenForAlerts(ClaimStatus.closedStatuses(), WINDOW_FROM)).isZero();
        assertThat(openInquiries(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50))).isEmpty();
        assertThat(customerInquiryRepository.countOpenForAlerts(InquiryStatus.openStatuses(), WINDOW_FROM)).isZero();
    }

    /** 🔴 한 번의 조회가 읽는 양은 페이지 크기로 고정된다 — 기간 안 전부를 읽지 않는다. */
    @Test
    void respectsPageableAndCursorTime() {
        for (int i = 1; i <= 5; i++) {
            line(order("O-" + i, ORDERED_AT.plusHours(i)), 1, 0, 0, "양말세트");
        }
        em.flush();
        em.clear();

        assertThat(newOrders(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 3))).hasSize(3);
        // 커서 시각보다 이전 것만 — 상한 자체는 배타적이다.
        assertThat(newOrders(WINDOW_FROM, ORDERED_AT.plusHours(3), Long.MIN_VALUE, PageRequest.of(0, 50)))
                .hasSize(2);
    }

    /**
     * 🔴 D12 의 핵심: 같은 시각(초까지 동일) 주문이 페이지 크기보다 많아도 커서로 끝까지 넘기면
     * <b>한 번씩만</b> 나온다. "넉넉히 읽고 여유분(+N)으로 거르는" 방식이었다면 여기서 행이 사라진다.
     */
    @Test
    void pagesThroughTiesWithoutLoss() {
        for (int i = 1; i <= 6; i++) {
            line(order("O-TIE-" + i, ORDERED_AT), 1, 0, 0, "양말세트");
        }
        em.flush();
        em.clear();

        List<Long> seen = new ArrayList<>();
        LocalDateTime cursorTime = CURSOR_NOW;
        Long cursorTieId = Long.MAX_VALUE;
        for (int page = 0; page < 10; page++) {
            List<NewOrderAlertRow> rows = newOrders(WINDOW_FROM, cursorTime, cursorTieId, PageRequest.of(0, 2));
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> seen.add(row.orderId()));
            NewOrderAlertRow last = rows.get(rows.size() - 1);
            cursorTime = last.orderedAt();
            cursorTieId = last.orderId();
        }

        assertThat(seen).hasSize(6).doesNotHaveDuplicates();
    }

    /**
     * 🔴 응답 매핑이 {@code seller.getSellerName()} 을 읽는다 — {@code @EntityGraph} 가 빠지면
     * {@code open-in-view=false} 아래서 LazyInitializationException 이다(2026-07-15 실제 장애).
     */
    @Test
    void loadsSellerWithoutLazyException() {
        claim(ORDERED_AT);
        inquiry(ORDERED_AT);
        em.flush();
        em.clear();

        List<OrderClaim> claims = openClaims(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50));
        List<CustomerInquiry> inquiries = openInquiries(WINDOW_FROM, CURSOR_NOW, Long.MAX_VALUE, PageRequest.of(0, 50));
        em.clear();      // 세션 밖 — 즉시 로딩이 안 됐으면 아래에서 터진다

        assertThat(claims).singleElement().satisfies(claim ->
                assertThat(claim.getMarketplaceAccount().getSeller().getSellerName()).isEqualTo("셀러A"));
        assertThat(inquiries).singleElement().satisfies(inquiry ->
                assertThat(inquiry.getMarketplaceAccount().getSeller().getSellerName()).isEqualTo("셀러A"));
    }

    // ------------------------------------------------------------- fixtures

    private List<NewOrderAlertRow> newOrders(LocalDateTime from, LocalDateTime cursorTime,
                                             Long cursorTieId, Pageable pageable) {
        return orderLineRepository.findNewOrderAlerts(OrderStatus.PAID, from, cursorTime, cursorTieId, pageable);
    }

    private List<OrderClaim> openClaims(LocalDateTime from, LocalDateTime cursorTime,
                                        Long cursorTieId, Pageable pageable) {
        return orderClaimRepository.findOpenForAlerts(ClaimStatus.closedStatuses(), from,
                cursorTime, cursorTieId, pageable);
    }

    private List<CustomerInquiry> openInquiries(LocalDateTime from, LocalDateTime cursorTime,
                                                Long cursorTieId, Pageable pageable) {
        return customerInquiryRepository.findOpenForAlerts(InquiryStatus.openStatuses(), from,
                cursorTime, cursorTieId, pageable);
    }

    private Order order(String externalOrderId, LocalDateTime orderedAt) {
        return em.persist(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(externalOrderId).orderedAt(orderedAt).build());
    }

    private void line(Order order, int orderQty, int cancelQty, int holdQty, String itemName) {
        em.persist(OrderLine.builder()
                .order(order).status(OrderStatus.PAID).itemName(itemName)
                .orderQty(orderQty).cancelQty(cancelQty).holdQty(holdQty)
                .unitPrice(new BigDecimal("1000"))
                .lineAmount(new BigDecimal("1000").multiply(BigDecimal.valueOf(orderQty)))
                .discountAmount(BigDecimal.ZERO)
                .build());
    }

    private void claim(LocalDateTime receivedAt) {
        em.persist(OrderClaim.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .claimType(ClaimType.RETURN)
                .externalClaimId("R-" + System.nanoTime())
                .externalOrderId("O-1").externalItemId("V-1")
                .itemName("양말세트").quantity(1)
                .status(ClaimStatus.RECEIVED).platformStatus("RETURNS_UNCHECKED")
                .reasonText("단순변심").requesterName("홍길동")
                .receivedAt(receivedAt).syncedAt(receivedAt)
                .orderItemMatchAttempts(0)
                .build());
    }

    private void inquiry(LocalDateTime inquiredAt) {
        em.persist(CustomerInquiry.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("Q-" + System.nanoTime())
                .externalOrderId("O-1").itemName("양말세트")
                .content("언제 배송되나요?")
                .status(InquiryStatus.UNANSWERED).platformStatus("requestAnswer")
                .inquiredAt(inquiredAt).lastSyncedAt(inquiredAt)
                .build());
    }
}
