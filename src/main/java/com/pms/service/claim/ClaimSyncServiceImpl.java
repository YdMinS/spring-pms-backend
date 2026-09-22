package com.pms.service.claim;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ClaimSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.AccountSyncLock;
import com.pms.service.coupang.CoupangReturnSyncService;
import com.pms.service.coupang.SyncStatusRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link ClaimSyncService} 구현 (FEATURE_2609_70 / D14·D15·D15-1).
 *
 * <p>⚠️ {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다({@code OrderSyncFacadeImpl}·
 * {@code InquirySyncServiceImpl} 과 같은 자세). DB 쓰기는 기존 upserter 들의 {@code REQUIRES_NEW}
 * 안에서만 일어난다.
 *
 * <p>🔴 <b>락을 잡는다</b> — 문의 경로와 갈리는 유일한 지점이다. 문의는 자기 테이블만 쓰지만 클레임은
 * 주문 동기화와 <b>같은 테이블</b>에 쓰므로, 열쇠를 나누거나 빼면 2609_48 이 막아둔 이중 적재가
 * 되살아난다. 그래서 열쇠는 주문과 같은 {@code (계정, ORDER)} 다.
 *
 * <p>🔴 <b>{@code syncCancels}·{@code trackOpenClaims} 실패는 그대로 올린다</b>(D15-1).
 * {@code OrderSyncFacadeImpl} 은 추적 실패를 삼키고 로그만 남기는데, 거기서는 <b>주문이 주인공</b>이라
 * 추적 실패가 주문 회차를 깨면 안 되기 때문이다. 이 입구는 반대로 <b>클레임만 보러</b> 누른 것이라,
 * 추적이 실패했는데 200 을 돌려주면 화면이 "방금 최신이 됐다"고 거짓말한다. 채널별 실패 격리는
 * 화면이 한다(문의 전례와 같은 계약). {@code last_claim_sync_at} 이 갱신되지 않는 것은 양쪽 같다.
 *
 * <p>🔴 <b>주문 조회(ordersheets)·문의 적재 의존성은 아예 주입하지 않는다</b> — 부를 수 없게 만드는 것이
 * 이 클래스의 범위를 지키는 방법이다. {@link SyncStatusRecorder} 도
 * {@code recordClaimSyncCompleted} 하나만 쓴다: 클레임만 돌고 {@code lastSyncStatus} 를 SUCCESS 로
 * 바꾸면 주문이 실패한 채널이 정상으로 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimSyncServiceImpl implements ClaimSyncService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final AccountSyncLock accountSyncLock;
    private final CoupangReturnSyncService coupangReturnSyncService;
    private final List<ClaimSyncAdapter> claimSyncAdapters;
    private final ClaimOrderBackfillService claimOrderBackfillService;
    private final SyncStatusRecorder syncStatusRecorder;

    @Override
    public ClaimSyncResponse sync(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));

        if (!Platform.COUPANG.equals(account.getPlatform())) {
            throw new IllegalArgumentException(
                    "클레임 조회를 지원하지 않는 채널입니다: " + account.getPlatform());
        }

        AccountSyncLock.Lease lease =
                accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, account.getId());
        if (lease == null) {
            // 🔴 쿠팡을 치지 않고 즉시 돌려준다. 기록기도 부르지 않는다 — 건너뛴 회차는 성공도 실패도
            // 아니라서, 기록하면 "마지막 동기화" 시각이 실제로 조회하지 않은 시각으로 밀린다.
            log.info("Claim sync skipped (already running): account={} heldSec={}",
                    account.getId(),
                    accountSyncLock.heldSeconds(AccountSyncLock.SyncWork.ORDER, account.getId()));
            return new ClaimSyncResponse(account.getId(), true, LocalDateTime.now());
        }

        // try-with-resources 라 본문이 예외를 던져도 빠져나가기 전에 풀린다 —
        // "실패했으니 곧바로 다시 누른다" 가 성립해야 한다.
        try (lease) {
            return syncLocked(account);
        }
    }

    /** 락을 쥔 상태의 본문. 순서는 {@code OrderSyncFacadeImpl#syncOneLocked} 의 클레임 구간 그대로다. */
    private ClaimSyncResponse syncLocked(MarketplaceAccount account) {
        // 저장되는 클레임이 이 계정의 테넌트로 들어가게 한다(문의 경로와 같은 이유·같은 방식).
        // 웹 요청은 이미 토큰의 테넌트가 세팅돼 있으므로 이전 값을 저장해 두었다가 되돌린다 —
        // clear() 로 통일하면 직후 목록 조회가 빈다.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());

            // 🔴 신규 반품 적재가 취소 보정 응답에 얹혀 있다(2609_18 D15) — 빼면 반품이 안 들어온다.
            coupangReturnSyncService.syncCancels(account);

            coupangReturnSyncService.trackOpenClaims(account);
            // 추적까지 끝난 회차에만 찍는다(파사드와 동일). 순서를 바꾸면 놓친 구간이 영구히 사라진다.
            syncStatusRecorder.recordClaimSyncCompleted(account.getId());

            // 교환 적재는 백필 앞이다 — 새로 생긴 미연결 claim 을 같은 회차에 처리하려면 백필이 뒤여야 한다.
            try {
                claimSyncAdapters.stream()
                        .filter(a -> a.platform().equals(account.getPlatform()))
                        .findFirst()
                        .ifPresent(a -> a.syncExchanges(account));
            } catch (Exception e) {
                // 교환 실패는 회차를 깨지 않는다 — 반품(Stage A)은 이미 적재됐다(파사드와 같은 판단).
                log.warn("Exchange claim sync failed (isolated): account={}", account.getId(), e);
            }

            try {
                claimOrderBackfillService.backfill(account);
            } catch (Exception e) {
                // 백필은 정확도 보정이다 — 실패해도 미연결 클레임은 화면에 "주문 미연결" 로 이미 보인다.
                log.warn("Claim order backfill failed (isolated): account={}", account.getId(), e);
            }

            log.info("Claim-only sync: account={}", account.getId());
            return new ClaimSyncResponse(account.getId(), false, LocalDateTime.now());
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
