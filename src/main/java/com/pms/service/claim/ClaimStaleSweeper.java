package com.pms.service.claim;

import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 미완결 클레임의 STALE 강제 종결 (FEATURE_2609_18 / D11). 반품(05)·교환(06)이 공유한다.
 *
 * 종결 신호를 영영 못 받는 건이 추적 대상에 영구히 남는 것을 막는다. <b>강제 종결이지 삭제가 아니다.</b>
 *
 * <p>⚠️ {@code ClaimType} 을 받지 않는다 — 넘겨받은 리스트만 보므로 이미 타입 무관이다.
 * 타입은 부르는 쪽의 {@code findOpen} 이 정한다.
 * <p>⚠️ 여기서 {@code findOpen} 을 치지 않는다 — 호출자가 같은 리스트를 슬라이스 계산에도 쓰므로,
 * 스위퍼가 스스로 조회하면 같은 쿼리를 두 번 치고 "미완결 0건이면 쿠팡을 아예 안 친다" 가드가
 * 재조회 결과에 의존하게 된다.
 * <p>⚠️ 벌크 {@code @Modifying} UPDATE 를 쓰지 않는다 — {@code @TenantId} 필터가 JPQL 벌크 갱신에
 * 적용되는지 확신할 수 없다. 건수가 작으므로 로드 후 개별 저장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimStaleSweeper {

    private final OrderClaimRepository orderClaimRepository;
    private final CoupangProperties coupangProperties;

    /**
     * 컷오프보다 오래된 미완결 건을 {@code STALE} 로 종결한다.
     *
     * @param open 미완결 클레임(호출자가 {@code findOpen} 으로 조회한 것)
     * @return 아직 살아 있는(추적 대상) 클레임 — 종결 건수는 호출자가 {@code open.size() - remaining.size()} 로 센다
     */
    public List<OrderClaim> sweep(MarketplaceAccount account, List<OrderClaim> open) {
        // ⚠️ receivedAt 은 쿠팡 createdAt = KST 벽시계(naive)다. LocalDateTime.now() 는 서버 UTC(naive) 라
        // 그대로 비교하면 9시간 어긋난다(프로젝트의 알려진 지뢰: paidAt KST vs audit UTC).
        LocalDateTime cutoff = LocalDate.now(SyncWindow.KST)
                .minusDays(coupangProperties.getClaimStaleDays()).atStartOfDay();

        List<OrderClaim> remaining = new ArrayList<>();
        int closed = 0;
        for (OrderClaim claim : open) {
            if (claim.getReceivedAt() != null && claim.getReceivedAt().isBefore(cutoff)) {
                orderClaimRepository.save(claim.toBuilder().status(ClaimStatus.STALE).build());
                closed++;
            } else {
                remaining.add(claim);
            }
        }
        log.info("Claim stale sweep: account={} count={} open={}", account.getId(), closed, remaining.size());
        return remaining;
    }
}
