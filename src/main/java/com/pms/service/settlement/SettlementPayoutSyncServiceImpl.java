package com.pms.service.settlement;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.SettlementPayoutSyncResponse;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.settlement.SettlementPayoutUpserter.PayoutUpsertResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SettlementPayoutSyncService} 구현 (FEATURE_2609_30 / 02 · PLAN D5-3·D5-5·D11).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다. DB 쓰기는
 * {@link SettlementPayoutUpserter}(묶음당 REQUIRES_NEW)와 {@link SettlementSyncStatusWriter} 에서만 일어난다.
 *
 * <p>🔴 <b>처리 순서를 {@code settlementDate ASC} 로 고정</b>한다(D5-5). 라인 귀속은 "주인 없는 라인을 먼저 온
 * 묶음이 가져간다"는 규칙이라 순서가 결과를 바꾼다 — 같은 입력이면 항상 같은 결과여야 재실행이 안전하다.
 *
 * <p>🔴 한 계정의 실패가 나머지를 중단시키지 않는다. 실패한 계정의 앵커는 갱신하지 않아 다음 회차가 다시 읽는다.
 */
@Slf4j
@Service
public class SettlementPayoutSyncServiceImpl implements SettlementPayoutSyncService {

    /** 실패 사유 문구 상한 — 응답 바디를 그대로 싣지 않는다(PII·자격증명). */
    private static final int REASON_MAX = 200;

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final SettlementPayoutUpserter settlementPayoutUpserter;
    private final SettlementSyncStatusWriter settlementSyncStatusWriter;
    private final CoupangProperties coupangProperties;
    private final Map<Platform, SettlementSource> sources = new EnumMap<>(Platform.class);

    public SettlementPayoutSyncServiceImpl(MarketplaceAccountRepository marketplaceAccountRepository,
                                           SettlementPayoutUpserter settlementPayoutUpserter,
                                           SettlementSyncStatusWriter settlementSyncStatusWriter,
                                           CoupangProperties coupangProperties,
                                           List<SettlementSource> sources) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.settlementPayoutUpserter = settlementPayoutUpserter;
        this.settlementSyncStatusWriter = settlementSyncStatusWriter;
        this.coupangProperties = coupangProperties;
        sources.forEach(source -> this.sources.put(source.platform(), source));
    }

    @Override
    public SettlementPayoutSyncResponse syncPayouts(Long accountId, YearMonth month) {
        YearMonth current = YearMonth.from(LocalDate.now(SyncWindow.KST));
        if (month != null && month.isAfter(current)) {
            // 플랫폼이 400 을 주는 요청이다 — 우리가 먼저 거절해 원인 불명 500 을 만들지 않는다.
            throw new IllegalArgumentException("당월 이후의 매출인식월은 조회할 수 없습니다: " + month);
        }
        List<MarketplaceAccount> accounts = resolveAccounts(accountId);

        PayoutUpsertResult total = PayoutUpsertResult.empty();
        List<String> failed = new ArrayList<>();
        for (MarketplaceAccount account : accounts) {
            try {
                total = total.plus(runAccount(account, months(account, month, current)));
            } catch (RuntimeException e) {
                log.warn("Settlement payout sync failed for account={}, isolated and continue",
                        account.getId(), e);
                failed.add(describeFailure(account, e));
            }
        }
        return new SettlementPayoutSyncResponse(accounts.size(), total.payouts(),
                total.attributedLines(), total.adjustments(), failed);
    }

    /**
     * 계정 1건 적재.
     *
     * <p>⚠️ 비-웹 경로(@Scheduled)는 TenantContext 가 비어 @TenantId INSERT 가 NO_TENANT 로 떨어진다 —
     * 계정의 테넌트를 세팅하고 끝나면 원래 값으로 되돌린다(웹 경로의 요청 테넌트를 지우지 않게).
     */
    private PayoutUpsertResult runAccount(MarketplaceAccount account, List<YearMonth> months) {
        SettlementSource source = sources.get(account.getPlatform());
        Long previousTenant = TenantContext.get();
        PayoutUpsertResult result = PayoutUpsertResult.empty();
        try {
            TenantContext.set(account.getTenantId());
            for (YearMonth month : months) {
                List<SettlementPayoutDraft> drafts = new ArrayList<>(source.fetchPayouts(account, month));
                // 🔴 결정적 처리 순서(D5-5). null 지급일은 뒤로 보낸다.
                drafts.sort(Comparator.comparing(SettlementPayoutDraft::settlementDate,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                for (SettlementPayoutDraft draft : drafts) {
                    result = result.plus(settlementPayoutUpserter.upsert(account, draft));
                }
            }
            settlementSyncStatusWriter.writePayoutSyncAt(account.getId());
            return result;
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * 읽을 인식월 목록.
     *
     * <p>지정하면 그 달만. 아니면 최초 실행({@code lastPayoutSyncAt == null})은 백필 개월수만큼 거슬러
     * 올라가고, 이후에는 당월 + 직전월만 다시 읽는다 — 정정이 직전월까지 흔히 들어오기 때문이다.
     */
    private List<YearMonth> months(MarketplaceAccount account, YearMonth requested, YearMonth current) {
        if (requested != null) {
            return List.of(requested);
        }
        int back = account.getLastPayoutSyncAt() == null
                ? Math.max(1, coupangProperties.getPayoutBackfillMonths())
                : 2;
        List<YearMonth> months = new ArrayList<>();
        for (int i = back - 1; i >= 0; i--) {
            months.add(current.minusMonths(i));
        }
        return months;
    }

    private List<MarketplaceAccount> resolveAccounts(Long accountId) {
        if (accountId == null) {
            return marketplaceAccountRepository.findByIsActiveTrue().stream()
                    .filter(account -> sources.containsKey(account.getPlatform()))
                    .toList();
        }
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("채널 계정을 찾을 수 없습니다: " + accountId));
        if (!sources.containsKey(account.getPlatform())) {
            throw new IllegalArgumentException("정산 조회를 지원하지 않는 플랫폼입니다: " + account.getPlatform());
        }
        return List.of(account);
    }

    private String describeFailure(MarketplaceAccount account, RuntimeException e) {
        String label = account.getAccountAlias() != null ? account.getAccountAlias() : ("#" + account.getId());
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return label + ": " + (reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason);
    }
}
