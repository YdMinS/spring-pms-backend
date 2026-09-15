package com.pms.service.alert;

import com.pms.domain.ClaimStatus;
import com.pms.domain.InquiryStatus;
import com.pms.dto.response.AlertSummaryResponse;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AlertSummaryService} 구현 — count 쿼리 2개가 전부다(마켓 호출 0).
 *
 * <p>🔴 상태 집합을 여기에 나열하지 않는다. "무엇이 미완결인가"는 {@link ClaimStatus#isOpen()}·
 * {@link InquiryStatus#isOpen()} 이 소유하고, 여기서는 그 파생 헬퍼를 조회 인자로 넘길 뿐이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertSummaryServiceImpl implements AlertSummaryService {

    private final OrderClaimRepository orderClaimRepository;
    private final CustomerInquiryRepository customerInquiryRepository;

    @Override
    public AlertSummaryResponse summary() {
        return new AlertSummaryResponse(
                orderClaimRepository.countByStatusNotIn(ClaimStatus.closedStatuses()),
                customerInquiryRepository.countByStatusIn(InquiryStatus.openStatuses()));
    }
}
