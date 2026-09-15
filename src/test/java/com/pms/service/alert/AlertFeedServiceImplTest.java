package com.pms.service.alert;

import com.pms.config.CoupangProperties;
import com.pms.domain.AlertType;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.AlertFeedItemResponse;
import com.pms.dto.response.AlertFeedResponse;
import com.pms.dto.response.NewOrderAlertRow;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AlertFeedServiceImpl — 세 소스를 하나의 최신순 목록으로 합치고 시각 커서로 잇는다(FEATURE_2609_51 / D12).
 *
 * <p>🔴 여기서 지키는 것은 <b>조립 규칙</b>이다: 정렬 하나·소스별 페이지 크기·소스별 tie id·KST 상한.
 * 쿼리가 실제로 그렇게 자르는지는 {@code AlertFeedRepositoryTest}(@DataJpaTest)가 본다.
 */
@ExtendWith(MockitoExtension.class)
class AlertFeedServiceImplTest {

    private static final LocalDateTime T_NEW = LocalDateTime.of(2026, 9, 15, 12, 0);
    private static final LocalDateTime T_MID = LocalDateTime.of(2026, 9, 15, 11, 0);
    private static final LocalDateTime T_OLD = LocalDateTime.of(2026, 9, 15, 10, 0);

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private CustomerInquiryRepository customerInquiryRepository;

    private CoupangProperties coupangProperties;
    private AlertFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        // 🔴 기간 계산은 목이 아니라 실제 설정값으로 본다 — 이 테스트가 잡아야 하는 회귀가 바로 그 계산이다.
        coupangProperties = new CoupangProperties();
        service = new AlertFeedServiceImpl(orderLineRepository, orderClaimRepository,
                customerInquiryRepository, new AlertWindows(coupangProperties));
    }

    @Test
    void feedMergesOrdersClaimsAndInquiriesNewestFirst() {
        given(orderLineRepository.findNewOrderAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(orderRow(11L, T_MID)));
        given(orderClaimRepository.findOpenForAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(claim(21L, T_NEW)));
        given(customerInquiryRepository.findOpenForAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(inquiry(31L, T_OLD)));

        AlertFeedResponse result = service.feed(null, null, 50);

        assertThat(result.items()).extracting(AlertFeedItemResponse::alertType, AlertFeedItemResponse::refId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AlertType.CLAIM, 21L),
                        org.assertj.core.groups.Tuple.tuple(AlertType.ORDER, 11L),
                        org.assertj.core.groups.Tuple.tuple(AlertType.INQUIRY, 31L));
        // 한 장에 다 들어갔으면 끝이다.
        assertThat(result.nextCursor()).isNull();
        // 주문 행은 대표 상품명과 상품 수를 함께 싣는다(D7 의 "상품 3개").
        assertThat(result.items()).filteredOn(item -> item.alertType() == AlertType.ORDER)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.itemCount()).isEqualTo(3);
                    assertThat(item.externalOrderId()).isEqualTo("O-11");
                    assertThat(item.platform()).isEqualTo(Platform.COUPANG.name());
                    assertThat(item.sellerName()).isEqualTo("셀러A");
                    assertThat(item.detail()).isNull();
                });
        // 클레임 행은 반품/교환 탭을 고르는 데 쓰는 종류를 싣고, 고객 이름은 싣지 않는다(2609_23 D13).
        assertThat(result.items()).filteredOn(item -> item.alertType() == AlertType.CLAIM)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.claimType()).isEqualTo(ClaimType.RETURN);
                    assertThat(item.detail()).isEqualTo("단순변심");
                    assertThat(item.itemCount()).isNull();
                });
    }

    /** 🔴 탭을 고르면 소스가 하나다 — 버릴 것을 조회하지도 않는다(D13). */
    @Test
    void feedFiltersByType() {
        given(orderLineRepository.findNewOrderAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(orderRow(11L, T_MID)));

        AlertFeedResponse result = service.feed(AlertType.ORDER, null, 50);

        assertThat(result.items()).singleElement()
                .extracting(AlertFeedItemResponse::alertType).isEqualTo(AlertType.ORDER);
        verify(orderClaimRepository, never()).findOpenForAlerts(any(), any(), any(), any(), any());
        verify(customerInquiryRepository, never()).findOpenForAlerts(any(), any(), any(), any(), any());
    }

    @Test
    void feedTruncatesDetailTo200() {
        given(customerInquiryRepository.findOpenForAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(inquiry(31L, T_OLD, "가".repeat(300))));

        AlertFeedResponse result = service.feed(AlertType.INQUIRY, null, 50);

        assertThat(result.items()).singleElement()
                .extracting(AlertFeedItemResponse::detail)
                .satisfies(detail -> assertThat((String) detail).hasSize(200));
    }

    @Test
    void feedReturnsNextCursorOfLastRow() {
        given(orderLineRepository.findNewOrderAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(orderRow(11L, T_NEW), orderRow(12L, T_MID)));

        // 한 장이 꽉 찼다 → 다음 커서는 마지막 행의 튜플이다.
        AlertFeedResponse full = service.feed(AlertType.ORDER, null, 2);
        assertThat(full.items()).hasSize(2);
        assertThat(full.nextCursor()).isEqualTo(T_MID + "|ORDER|12");

        // 덜 찼다 → 끝이다.
        AlertFeedResponse partial = service.feed(AlertType.ORDER, null, 3);
        assertThat(partial.nextCursor()).isNull();
    }

    /**
     * 🔴 D12 의 동률 처리: 커서와 <b>같은 시각</b>의 이미 보낸 건은 다음 장에 다시 나오지 않고
     * (쿼리에 {@code id < tieId} 로 걸린다), 같은 시각의 아직 안 보낸 건은 빠지지 않는다
     * (서비스가 커서로 <b>다시 거르지 않는다</b> — 두 곳에서 거르면 규칙이 갈라진다).
     */
    @Test
    void feedSkipsRowsAtOrBeforeCursor() {
        String cursor = new AlertCursor(T_MID, AlertType.CLAIM, 100L).encode();
        // 같은 시각의 다음 종류(INQUIRY)는 아직 안 보낸 것이다 — 그대로 실려야 한다.
        given(customerInquiryRepository.findOpenForAlerts(any(), any(), any(), any(), any()))
                .willReturn(List.of(inquiry(31L, T_MID)));

        AlertFeedResponse result = service.feed(null, cursor, 50);

        assertThat(result.items()).extracting(AlertFeedItemResponse::refId).containsExactly(31L);

        // 이미 보낸 쪽(같은 종류의 id ≤ 100)은 애초에 조회되지 않는다.
        ArgumentCaptor<LocalDateTime> cursorTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Long> tieId = ArgumentCaptor.forClass(Long.class);
        verify(orderClaimRepository).findOpenForAlerts(any(), any(), cursorTime.capture(), tieId.capture(), any());
        assertThat(cursorTime.getValue()).isEqualTo(T_MID);
        assertThat(tieId.getValue()).isEqualTo(100L);
    }

    /** 🔴 "기간 안 전부 읽기" 회귀 방지 — 소스마다 정확히 페이지 크기만 읽는다(여유분 없음). */
    @Test
    void feedReadsOnlyPageSizePerSource() {
        service.feed(null, null, 7);

        ArgumentCaptor<Pageable> orderPage = ArgumentCaptor.forClass(Pageable.class);
        verify(orderLineRepository).findNewOrderAlerts(any(), any(), any(), any(), orderPage.capture());
        ArgumentCaptor<Pageable> claimPage = ArgumentCaptor.forClass(Pageable.class);
        verify(orderClaimRepository).findOpenForAlerts(any(), any(), any(), any(), claimPage.capture());
        ArgumentCaptor<Pageable> inquiryPage = ArgumentCaptor.forClass(Pageable.class);
        verify(customerInquiryRepository).findOpenForAlerts(any(), any(), any(), any(), inquiryPage.capture());

        assertThat(List.of(orderPage.getValue(), claimPage.getValue(), inquiryPage.getValue()))
                .allSatisfy(pageable -> {
                    assertThat(pageable.getPageNumber()).isZero();
                    assertThat(pageable.getPageSize()).isEqualTo(7);
                });
    }

    /** 🔴 하한은 스윕과 같은 설정값의 KST 자정이다(D8) — 새 설정을 만들면 유령 건이 생긴다. */
    @Test
    void feedUsesSweepWindows() {
        service.feed(null, null, 10);

        LocalDate today = LocalDate.now(SyncWindow.KST);

        ArgumentCaptor<LocalDateTime> orderFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderLineRepository).findNewOrderAlerts(eq(OrderStatus.PAID), orderFrom.capture(),
                any(), any(), any());
        assertThat(orderFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getSyncDays()).atStartOfDay());

        ArgumentCaptor<LocalDateTime> claimFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderClaimRepository).findOpenForAlerts(eq(ClaimStatus.closedStatuses()), claimFrom.capture(),
                any(), any(), any());
        assertThat(claimFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getClaimStaleDays()).atStartOfDay());

        ArgumentCaptor<LocalDateTime> inquiryFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(customerInquiryRepository).findOpenForAlerts(eq(InquiryStatus.openStatuses()),
                inquiryFrom.capture(), any(), any(), any());
        assertThat(inquiryFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getInquiryStaleDays()).atStartOfDay());
    }

    /**
     * 🔴 커서 없이 부른 첫 장의 상한은 <b>KST 기준 현재</b>다(D8).
     *
     * <p>{@code LocalDateTime.now()}(서버 UTC)로 회귀하면 최근 9시간에 들어온 건이 통째로 사라지는데,
     * 그걸 잡는 테스트는 이것뿐이다 — 배지(상한 없음)만 올라가고 목록엔 안 보이는 상태가 된다.
     */
    @Test
    void feedFirstPageUsesKstUpperBound() {
        LocalDateTime before = LocalDateTime.now(SyncWindow.KST);

        service.feed(AlertType.ORDER, null, 10);

        LocalDateTime after = LocalDateTime.now(SyncWindow.KST);
        ArgumentCaptor<LocalDateTime> cursorTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderLineRepository).findNewOrderAlerts(any(), any(), cursorTime.capture(), any(), any());
        assertThat(cursorTime.getValue()).isBetween(before, after);
    }

    /**
     * 🔴 소스별 tie id (D12): 커서가 {@code CLAIM} 이면 클레임 = 커서 id, 종류 순서상 뒤인 문의 =
     * {@code Long.MAX_VALUE}(그 시각 전부 아직 안 보냄), 앞인 주문 = {@code Long.MIN_VALUE}(그 시각은 다 보냄).
     */
    @Test
    void feedPassesTieIdPerSource() {
        String cursor = new AlertCursor(T_MID, AlertType.CLAIM, 100L).encode();

        service.feed(null, cursor, 10);

        ArgumentCaptor<Long> orderTie = ArgumentCaptor.forClass(Long.class);
        verify(orderLineRepository).findNewOrderAlerts(any(), any(), any(), orderTie.capture(), any());
        assertThat(orderTie.getValue()).isEqualTo(Long.MIN_VALUE);

        ArgumentCaptor<Long> claimTie = ArgumentCaptor.forClass(Long.class);
        verify(orderClaimRepository).findOpenForAlerts(any(), any(), any(), claimTie.capture(), any());
        assertThat(claimTie.getValue()).isEqualTo(100L);

        ArgumentCaptor<Long> inquiryTie = ArgumentCaptor.forClass(Long.class);
        verify(customerInquiryRepository).findOpenForAlerts(any(), any(), any(), inquiryTie.capture(), any());
        assertThat(inquiryTie.getValue()).isEqualTo(Long.MAX_VALUE);
    }

    /** 깨진 커서는 예외가 아니라 첫 장이다 — 사용자가 오래된 링크를 눌렀을 뿐이다. */
    @Test
    void feedFallsBackToFirstPageOnBrokenCursor() {
        service.feed(AlertType.ORDER, "쓰레기값", 10);

        ArgumentCaptor<Long> tieId = ArgumentCaptor.forClass(Long.class);
        verify(orderLineRepository).findNewOrderAlerts(any(), any(), any(), tieId.capture(), any());
        assertThat(tieId.getValue()).isEqualTo(Long.MAX_VALUE);
    }

    // ------------------------------------------------------------- fixtures

    private NewOrderAlertRow orderRow(long orderId, LocalDateTime orderedAt) {
        return new NewOrderAlertRow(orderId, "O-" + orderId, orderedAt, Platform.COUPANG,
                "셀러A", "양말세트", 3L);
    }

    private OrderClaim claim(long id, LocalDateTime receivedAt) {
        return OrderClaim.builder()
                .id(id)
                .marketplaceAccount(account())
                .platform(Platform.COUPANG)
                .claimType(ClaimType.RETURN)
                .externalClaimId("R-" + id)
                .externalOrderId("O-" + id)
                .externalItemId("V-" + id)
                .itemName("양말세트")
                .quantity(1)
                .status(ClaimStatus.RECEIVED)
                .platformStatus("RETURNS_UNCHECKED")
                .reasonText("단순변심")
                .requesterName("홍길동")
                .receivedAt(receivedAt)
                .syncedAt(receivedAt)
                .orderItemMatchAttempts(0)
                .build();
    }

    private CustomerInquiry inquiry(long id, LocalDateTime inquiredAt) {
        return inquiry(id, inquiredAt, "언제 배송되나요?");
    }

    private CustomerInquiry inquiry(long id, LocalDateTime inquiredAt, String content) {
        return CustomerInquiry.builder()
                .id(id)
                .marketplaceAccount(account())
                .platform(Platform.COUPANG)
                .inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("Q-" + id)
                .externalOrderId("O-" + id)
                .itemName("양말세트")
                .content(content)
                .status(InquiryStatus.UNANSWERED)
                .platformStatus("requestAnswer")
                .inquiredAt(inquiredAt)
                .lastSyncedAt(inquiredAt)
                .build();
    }

    private MarketplaceAccount account() {
        return MarketplaceAccountFixture.coupangStubBuilder()
                .seller(Seller.builder().sellerName("셀러A").build())
                .build();
    }
}
