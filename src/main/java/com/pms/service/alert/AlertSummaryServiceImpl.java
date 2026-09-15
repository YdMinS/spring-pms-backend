package com.pms.service.alert;

import com.pms.domain.ClaimStatus;
import com.pms.domain.InquiryStatus;
import com.pms.domain.OrderStatus;
import com.pms.dto.response.AlertSummaryResponse;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@link AlertSummaryService} 구현 — count 쿼리만 쓴다(마켓 호출 0).
 *
 * <p>🔴 상태 집합을 여기에 나열하지 않는다. "무엇이 미완결인가"는 {@link ClaimStatus#isOpen()}·
 * {@link InquiryStatus#isOpen()} 이 소유하고, 여기서는 그 파생 헬퍼를 조회 인자로 넘길 뿐이다.
 *
 * <p>🔴 <b>피드를 만들어서 세지 않는다</b>(FEATURE_2609_51) — 화면이 60초마다 부르는 경로다.
 * 기간은 {@link AlertWindows} 에서만 가져온다: 목록과 다른 하한을 쓰면 배지와 행 수가 어긋난다(D3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertSummaryServiceImpl implements AlertSummaryService {

    /** 결제완료 = 출고관리 배지·새 주문 알림의 조건(2609_51 D5·D7). */
    private static final OrderStatus PAID = OrderStatus.PAID;

    private final OrderClaimRepository orderClaimRepository;
    private final CustomerInquiryRepository customerInquiryRepository;
    private final OrderLineRepository orderLineRepository;
    private final AlertWindows alertWindows;

    @Override
    public AlertSummaryResponse summary() {
        LocalDateTime orderFrom = alertWindows.orderFrom();
        long newOrders = orderLineRepository.countNewOrders(PAID, orderFrom);
        // 🔴 todoCount 는 사이드바 배지 두 개를 더한 값이 아니다 — 목록과 같은 기간이 걸린 카운트를 쓴다(D3).
        long openClaimsInWindow = orderClaimRepository.countOpenForAlerts(
                ClaimStatus.closedStatuses(), alertWindows.claimFrom());
        long openInquiriesInWindow = customerInquiryRepository.countOpenForAlerts(
                InquiryStatus.openStatuses(), alertWindows.inquiryFrom());

        return new AlertSummaryResponse(
                // 🔴 기존 두 값은 기간 무관이다 — 값이 바뀌면 사이드바 배지가 조용히 달라진다.
                orderClaimRepository.countByStatusNotIn(ClaimStatus.closedStatuses()),
                customerInquiryRepository.countByStatusIn(InquiryStatus.openStatuses()),
                orderLineRepository.countPaidLines(PAID),
                newOrders,
                newOrders + openClaimsInWindow + openInquiriesInWindow);
    }
}
