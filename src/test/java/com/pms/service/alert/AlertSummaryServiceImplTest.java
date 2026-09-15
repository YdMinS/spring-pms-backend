package com.pms.service.alert;

import com.pms.domain.ClaimStatus;
import com.pms.domain.InquiryStatus;
import com.pms.dto.response.AlertSummaryResponse;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * AlertSummaryServiceImpl — 카운트 2개를 그대로 싣고, 상태 집합은 enum 파생 헬퍼에서만 온다(D9).
 */
@ExtendWith(MockitoExtension.class)
class AlertSummaryServiceImplTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private CustomerInquiryRepository customerInquiryRepository;

    @InjectMocks private AlertSummaryServiceImpl service;

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
}
