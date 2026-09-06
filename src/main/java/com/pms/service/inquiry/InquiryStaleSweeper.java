package com.pms.service.inquiry;

import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 오래된 미답변 문의의 STALE 강제 종결 (FEATURE_2609_23 / D9). {@code ClaimStaleSweeper} 와 같은 장치다.
 *
 * 조회 앵커(D8)는 "가장 오래된 미답변" 을 끌어들이므로, 답이 영영 달리지 않는 건이 남아 있으면 앵커가
 * 무한히 뒤로 밀려 슬라이스가 폭주한다. 이 스윕이 그 하한을 만든다 — <b>강제 종결이지 삭제가 아니다.</b>
 *
 * <p>⚠️ 벌크 {@code @Modifying} UPDATE 를 쓰지 않는다 — {@code @TenantId} 필터가 JPQL 벌크 갱신에
 * 적용되는지 확신할 수 없다. 건수가 작으므로 로드 후 개별 저장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryStaleSweeper {

    private final CustomerInquiryRepository customerInquiryRepository;
    private final CoupangProperties coupangProperties;

    /**
     * 컷오프보다 오래된 미답변 건을 {@code STALE} 로 종결한다.
     *
     * @return 종결한 건수
     */
    public int sweep(MarketplaceAccount account) {
        // ⚠️ inquiredAt 은 쿠팡 inquiryAt = KST 벽시계(naive)다. LocalDateTime.now() 는 서버 UTC(naive)라
        // 그대로 비교하면 9시간 어긋난다(프로젝트의 알려진 지뢰: paidAt KST vs audit UTC).
        LocalDateTime cutoff = LocalDate.now(SyncWindow.KST)
                .minusDays(coupangProperties.getInquiryStaleDays()).atStartOfDay();

        List<CustomerInquiry> stale = customerInquiryRepository
                .findByMarketplaceAccount_IdAndStatusAndInquiredAtBefore(
                        account.getId(), InquiryStatus.UNANSWERED, cutoff);
        for (CustomerInquiry inquiry : stale) {
            customerInquiryRepository.save(inquiry.toBuilder().status(InquiryStatus.STALE).build());
        }
        if (!stale.isEmpty()) {
            log.info("Inquiry stale sweep: account={} count={}", account.getId(), stale.size());
        }
        return stale.size();
    }
}
