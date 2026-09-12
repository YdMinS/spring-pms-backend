package com.pms.service.inquiry;

import com.pms.domain.MarketplaceAccount;
import com.pms.dto.response.InquirySyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.inquiry.InquirySyncAdapter.InquirySyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link InquirySyncService} 구현.
 *
 * <p>⚠️ {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다({@code OrderSyncFacadeImpl} 과 같은
 * 자세). DB 쓰기는 {@link InquiryUpserter}(REQUIRES_NEW)·{@link InquiryStaleSweeper} 안에서만 일어난다.
 *
 * <p>⚠️ 실패하면 <b>예외를 그대로 올린다</b>. 실패를 성공처럼 돌려주면 마지막 조회 시각이 갱신돼
 * 놓친 구간이 영구히 사라진다(어댑터가 같은 이유로 예외를 던진다). 채널별 실패 격리는 화면이 한다 —
 * 주문 동기화의 단건 호출과 같은 계약이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquirySyncServiceImpl implements InquirySyncService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final List<InquirySyncAdapter> inquirySyncAdapters;
    private final SyncStatusRecorder syncStatusRecorder;

    @Override
    public InquirySyncResponse sync(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));

        InquirySyncAdapter adapter = inquirySyncAdapters.stream()
                .filter(a -> a.platform().equals(account.getPlatform()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "문의 조회를 지원하지 않는 채널입니다: " + account.getPlatform()));

        // 저장되는 문의가 이 계정의 테넌트로 들어가게 한다(파사드 syncOne 과 같은 이유·같은 방식).
        // 웹 요청은 이미 토큰의 테넌트가 세팅돼 있으므로 이전 값을 저장해 두었다가 되돌린다.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());

            InquirySyncResult result = adapter.syncInquiries(account);
            // 성공한 회차에만 기록한다 — 실패인데 갱신하면 다음 회차가 놓친 구간을 다시 읽지 않는다.
            syncStatusRecorder.recordInquirySyncCompleted(account.getId());
            log.info("Inquiry-only sync: account={} fetched={} staleClosed={}",
                    account.getId(), result.upserted(), result.staleClosed());
            return InquirySyncResponse.of(account.getId(), result);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
