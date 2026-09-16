package com.pms.service.alert;

import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.InquiryStatus;
import com.pms.domain.OrderStatus;
import com.pms.dto.response.AlertSummaryResponse;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * AlertSummaryServiceImpl — 카운트만 싣고, 상태 집합은 enum 파생 헬퍼에서만 온다(2609_49 D9).
 *
 * <p>🔴 {@code todoCount} 는 사이드바 배지 두 개의 합이 아니다 — 목록과 같은 기간이 걸린 카운트를 쓴다
 * (2609_51 D3). 그 차이가 자동 종결 고장의 신호다.
 */
@ExtendWith(MockitoExtension.class)
class AlertSummaryServiceImplTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private OrderLineRepository orderLineRepository;

    private CoupangProperties coupangProperties;
    private AlertSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        coupangProperties = new CoupangProperties();
        service = new AlertSummaryServiceImpl(orderClaimRepository, customerInquiryRepository,
                orderLineRepository, new AlertWindows(coupangProperties));
    }

    @Test
    void summary_carriesBothCounts() {
        given(orderClaimRepository.countByStatusNotIn(any())).willReturn(3L);
        given(customerInquiryRepository.countByStatusIn(any())).willReturn(5L);

        AlertSummaryResponse result = service.summary();

        assertThat(result.openClaims()).isEqualTo(3);
        assertThat(result.unansweredInquiries()).isEqualTo(5);
    }

    @Test
    void summary_usesDerivedStatusSets_notHandWrittenLists() {
        // 🔴 상태 목록을 서비스·쿼리에 복제하면 정의가 두 벌이 된다 — 파생 헬퍼와 동일해야 한다.
        given(orderClaimRepository.countByStatusNotIn(any())).willReturn(0L);
        given(customerInquiryRepository.countByStatusIn(any())).willReturn(0L);

        service.summary();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ClaimStatus>> closed = ArgumentCaptor.forClass(Collection.class);
        verify(orderClaimRepository).countByStatusNotIn(closed.capture());
        assertThat(closed.getValue())
                .containsExactlyElementsOf(ClaimStatus.closedStatuses())
                .containsExactlyInAnyOrder(ClaimStatus.DONE, ClaimStatus.REJECTED,
                        ClaimStatus.WITHDRAWN, ClaimStatus.STALE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<InquiryStatus>> open = ArgumentCaptor.forClass(Collection.class);
        verify(customerInquiryRepository).countByStatusIn(open.capture());
        assertThat(open.getValue())
                .containsExactlyElementsOf(InquiryStatus.openStatuses())
                .containsExactly(InquiryStatus.UNANSWERED);
    }

    /** 🔴 종 배지 = 새 주문 + 기간이 걸린 클레임·문의 (D3). 클라이언트가 더하지 않게 서버가 준다. */
    @Test
    void summaryAddsPaidOrdersNewOrdersAndTodoCount() {
        given(orderLineRepository.countPaidOrders(OrderStatus.PAID)).willReturn(9L);
        given(orderLineRepository.countNewOrders(eq(OrderStatus.PAID), any())).willReturn(4L);
        given(orderClaimRepository.countOpenForAlerts(any(), any())).willReturn(2L);
        given(customerInquiryRepository.countOpenForAlerts(any(), any())).willReturn(1L);

        AlertSummaryResponse result = service.summary();

        assertThat(result.paidOrders()).isEqualTo(9);
        assertThat(result.newOrders()).isEqualTo(4);
        assertThat(result.todoCount()).isEqualTo(7);      // 4 + 2 + 1

        // 하한은 피드와 같은 창이어야 한다(D8) — 어긋나면 배지와 목록 건수가 달라진다.
        LocalDate today = LocalDate.now(SyncWindow.KST);
        ArgumentCaptor<LocalDateTime> orderFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderLineRepository).countNewOrders(eq(OrderStatus.PAID), orderFrom.capture());
        assertThat(orderFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getSyncDays()).atStartOfDay());

        ArgumentCaptor<LocalDateTime> claimFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderClaimRepository).countOpenForAlerts(any(), claimFrom.capture());
        assertThat(claimFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getClaimStaleDays()).atStartOfDay());

        ArgumentCaptor<LocalDateTime> inquiryFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(customerInquiryRepository).countOpenForAlerts(any(), inquiryFrom.capture());
        assertThat(inquiryFrom.getValue())
                .isEqualTo(today.minusDays(coupangProperties.getInquiryStaleDays()).atStartOfDay());
    }

    /**
     * 🔴 {@code paidOrders}(기간 없음)와 {@code newOrders}(최근 sync-days)는 <b>같은 주문 단위</b>지만
     * 기간이 달라 여전히 다른 숫자다(D8) — 한쪽을 다른 쪽에 맞추는 회귀를 막는다.
     */
    @Test
    void summaryPaidOrdersAndNewOrdersAreDifferentNumbers() {
        given(orderLineRepository.countPaidOrders(OrderStatus.PAID)).willReturn(12L);
        given(orderLineRepository.countNewOrders(eq(OrderStatus.PAID), any())).willReturn(5L);

        AlertSummaryResponse result = service.summary();

        assertThat(result.paidOrders()).isEqualTo(12);
        assertThat(result.newOrders()).isEqualTo(5);
    }
}
