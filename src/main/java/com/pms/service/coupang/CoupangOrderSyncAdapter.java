package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.claim.ClaimOrderBackfillService;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.inquiry.InquirySyncAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Coupang order sync round (FEATURE_2610_23 / D21) — ordersheets (Phase2) → returnRequests cancel correction (§A) →
 * open-claim tracking (05) → exchange load (06) → claim order backfill (04) → customer inquiry load (2609_23 D12),
 * moved from {@link OrderSyncFacadeImpl} unchanged (D3).
 *
 * <p>⚠️ No {@code @Transactional} — the same reason as the facade: the inner services commit or roll back on their own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderSyncAdapter implements OrderSyncAdapter {

    private final CoupangProperties coupangProperties;
    private final CoupangOrderSyncService coupangOrderSyncService;
    private final CoupangReturnSyncService coupangReturnSyncService;
    private final SyncStatusRecorder syncStatusRecorder;
    private final ClaimOrderBackfillService claimOrderBackfillService;
    /** 교환 클레임 동기화 어댑터(D21). 플랫폼 미지원(네이버)·빈 리스트(local/test)면 조용히 건너뛴다. */
    private final List<ClaimSyncAdapter> claimSyncAdapters;
    /** 고객문의 동기화 어댑터(2609_23 D12·D19). 클레임 어댑터와 같은 자세로 조용히 건너뛴다. */
    private final List<InquirySyncAdapter> inquirySyncAdapters;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    /**
     * 락을 쥔 상태의 실제 동기화 본문.
     *
     * {@code preset} 은 ordersheets 가 조회할 상태와 창만 고른다 — 취소 보정·클레임·문의 단계와
     * 상태 기록은 프리셋과 무관하게 그대로 돈다(2609_16 D5·D6 · 2609_49 D7).
     *
     * {@code scheduled} 는 <b>기록기 호출만</b> 가른다(2609_49 / D13) — 스케줄 회차는 실패·부분 실패를
     * 기록하지 않는다. 🔴 프리셋으로 대신할 수 없다: QUICK 은 수동 버튼과 15분 스케줄이 공유한다.
     */
    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        // 주문 조회 = 프리셋이 정한다(2609_49 D1·D6). 나머지 단계는 프리셋과 무관하게 전부 돈다.
        SyncResult orders;
        try {
            orders = switch (preset) {
                case QUICK -> coupangOrderSyncService.syncAccount(account, OrderSyncScope.ACTIVE);
                // 🔴 전량 리컨실은 앵커를 쓰지 않는다 — QUICK 이 15분마다 앵커를 찍어 종결 창이 3일로
                // 붕괴하기 때문이다(OrderSyncPreset.RECONCILE 주석). 이 오버로드는 전 상태에 한 창을 쓴다.
                case RECONCILE -> coupangOrderSyncService.syncAccount(
                        account, SyncWindow.recent(coupangProperties.getSyncDays()));
            };
        } catch (RuntimeException e) {
            if (!scheduled) {
                syncStatusRecorder.recordFailure(account.getId(), e);
            }
            throw e;                    // 격리는 syncEach 담당, 단건은 전파(D4)
        }

        // 00(D15) 이후 일부 상태만 실패하면 예외가 아니라 failedStatuses 로 돌아온다(전 상태 실패는 예외).
        // 이걸 무시하고 SUCCESS 를 찍으면 2026-09-02 사고 계정이 "정상"으로 낙인된다(D18).
        String orderPartial = null;
        if (!orders.failedStatuses().isEmpty()) {
            // 일부 상태만 실패 — 성공한 상태는 이미 커밋됐다(PLAN D15).
            // 00(D17) 이 넣은 WARN 로그다. 지우지 말 것 — 사후 추적의 유일한 근거.
            log.warn("Order sync partial: account={} failedStatuses={}",
                    account.getId(), orders.failedStatuses());
            orderPartial = "주문 조회 일부 실패 — 상태: " + orders.failedStatuses().stream()
                    .map(Enum::name).collect(Collectors.joining(", "));
        }

        CancelSyncResult cancels;
        try {
            cancels = coupangReturnSyncService.syncCancels(account);
        } catch (RuntimeException e) {
            // Orders landed but cancellations did not: canceled lines can still look purchasable,
            // so this is NOT a success (PLAN D8). 취소 보정 사유를 앞에 둔다(더 위험한 쪽).
            if (!scheduled) {
                String reason = "취소 보정 실패 — " + SyncStatusRecorder.summarize(e)
                        + (orderPartial == null ? "" : " / " + orderPartial);
                syncStatusRecorder.recordPartial(account.getId(), reason, orderPartial == null, false);
            }
            throw e;
        }

        // 확정 순서: 취소 보정 → 추적 → 백필. 추적 슬라이스도 클레임을 적재하므로, 백필이 뒤에
        // 있어야 그때 새로 생긴 미연결 건까지 같은 회차에 처리된다(2609_18 05 Step 8).
        try {
            coupangReturnSyncService.trackOpenClaims(account);
            syncStatusRecorder.recordClaimSyncCompleted(account.getId());   // 추적까지 끝난 회차만
        } catch (Exception e) {
            log.warn("Claim tracking failed (isolated): account={}", account.getId(), e);
            // lastClaimSyncAt 미갱신 → 다음 회차 창이 자동으로 넓어져 놓친 구간을 덮는다(D18).
            // 취소 보정과 달리 SUCCESS 를 깨지 않는다 — 추적은 이미 적재된 건의 상태 따라잡기다.
        }

        // 교환 적재는 백필 앞이다 — 새로 생긴 미연결 claim 을 같은 회차에 처리하려면 백필이 뒤여야 한다.
        try {
            claimSyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> a.syncExchanges(account));
        } catch (Exception e) {
            // 교환은 신규 연동이다 — 실패해도 주문·취소·반품(Stage A)을 되돌리지 않는다(PLAN §9).
            log.warn("Exchange claim sync failed (isolated): account={}", account.getId(), e);
        }

        try {
            claimOrderBackfillService.backfill(account);
        } catch (Exception e) {
            // 백필은 정확도 보정이다 — 실패해도 주문·취소는 정상이므로 SUCCESS 를 깨지 않는다
            // (취소 보정과 다른 판단: 취소된 라인은 구매 가능해 보이지만, 미연결 클레임은
            // 화면에 "주문 미연결" 로 이미 보인다).
            log.warn("Claim order backfill failed (isolated): account={}", account.getId(), e);
        }

        // 문의 적재는 클레임 단계 뒤다(2609_23 D12) — 별도 스케줄러·별도 버튼을 만들지 않고 여기 합류한다.
        try {
            inquirySyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> {
                        a.syncInquiries(account);
                        // Stamped only on a successful round. A direct entity setter would be lost silently —
                        // this round runs outside a transaction — so it goes through the claims' recorder.
                        syncStatusRecorder.recordInquirySyncCompleted(account.getId());
                    });
        } catch (Exception e) {
            // 문의는 조회 기능이다 — 실패해도 주문·취소·클레임 결과를 되돌리지 않는다.
            // lastInquirySyncAt 미갱신 → 다음 회차 앵커가 자동으로 넓어져 놓친 구간을 덮는다.
            log.warn("Inquiry sync failed (isolated): account={}", account.getId(), e);
        }

        if (orderPartial != null) {
            // 스케줄 회차는 PARTIAL 을 낙인하지 않는다(D13) — 위 WARN 로그가 유일한 흔적이다.
            if (!scheduled) {
                syncStatusRecorder.recordPartial(account.getId(), orderPartial, false, true);
            }
        } else {
            // 🔴 성공은 스케줄 회차에서도 기록한다 — lastOrderSyncAt 이 배너·조회 창 앵커의 원천이다.
            syncStatusRecorder.recordSuccess(account.getId());
        }
        return new OrderSyncResult(
                LocalDateTime.now(),
                orders.newCount(),
                orders.updatedCount(),
                cancels.matchedUpdated(),
                0);
    }
}
