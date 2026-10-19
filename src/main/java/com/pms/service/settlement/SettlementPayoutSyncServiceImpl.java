package com.pms.service.settlement;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.SettlementPayoutSyncResponse;
import com.pms.exception.CoupangRateLimitedException;
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
 * {@link SettlementPayoutSyncService} implementation (FEATURE_2609_30 / 02 · PLAN D5-3 · D5-5 · D11).
 *
 * <p>⚠️ No class-level {@code @Transactional} — this is an outbound HTTP loop. DB writes happen only in
 * {@link SettlementPayoutUpserter} (REQUIRES_NEW per payout), {@link LocalPayoutBuilder} (REQUIRES_NEW, 11st payouts
 * built from stored lines — FEATURE_2610_20 / D30) and {@link SettlementSyncStatusWriter}.
 *
 * <p>🔴 <b>The processing order is fixed to {@code settlementDate ASC}</b> (D5-5). Line attribution is "the payout
 * that comes first takes the unowned lines", so the order changes the result — the same input must always give the
 * same result for a re-run to be safe.
 *
 * <p>🔴 One account's failure does not stop the rest. A failed account's anchor is not updated, so the next run
 * reads it again.
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
    private final Map<Platform, LocalPayoutBuilder> payoutBuilders = new EnumMap<>(Platform.class);

    public SettlementPayoutSyncServiceImpl(MarketplaceAccountRepository marketplaceAccountRepository,
                                           SettlementPayoutUpserter settlementPayoutUpserter,
                                           SettlementSyncStatusWriter settlementSyncStatusWriter,
                                           CoupangProperties coupangProperties,
                                           List<SettlementSource> sources,
                                           List<LocalPayoutBuilder> payoutBuilders) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.settlementPayoutUpserter = settlementPayoutUpserter;
        this.settlementSyncStatusWriter = settlementSyncStatusWriter;
        this.coupangProperties = coupangProperties;
        sources.forEach(source -> this.sources.put(source.platform(), source));
        payoutBuilders.forEach(builder -> this.payoutBuilders.put(builder.platform(), builder));
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
        int rateLimited = 0;
        CoupangRateLimitedException lastRateLimited = null;
        for (MarketplaceAccount account : accounts) {
            try {
                total = total.plus(runAccount(account, months(account, month, current), month == null));
            } catch (CoupangRateLimitedException e) {
                // 쿨다운은 계정별이다(FEATURE_2609_46 D1) — 한 계정의 429 로 나머지 계정을 중단하지 않는다.
                // 다만 계정이 전부 쿨다운이면 200 + failedAccounts 에는 재시도 가능 시각 문구가 화면에 뜨지 않으므로
                // (그 UX 는 429 응답에만 있다) 그때만 전파한다.
                rateLimited++;
                lastRateLimited = e;
                failed.add(describeFailure(account, e));   // 메시지에 재시도 가능 시각이 들어 있다
            } catch (RuntimeException e) {
                log.warn("Settlement payout sync failed for account={}, isolated and continue",
                        account.getId(), e);
                failed.add(describeFailure(account, e));
            }
        }
        if (rateLimited > 0 && rateLimited == accounts.size()) {
            throw lastRateLimited;      // 성공한 계정이 하나도 없다 — 오늘과 같은 429 UX 를 유지한다
        }
        return new SettlementPayoutSyncResponse(accounts.size(), total.payouts(),
                total.attributedLines(), total.adjustments(), failed);
    }

    /**
     * 계정 1건 적재.
     *
     * <p>⚠️ 비-웹 경로(@Scheduled)는 TenantContext 가 비어 @TenantId INSERT 가 NO_TENANT 로 떨어진다 —
     * 계정의 테넌트를 세팅하고 끝나면 원래 값으로 되돌린다(웹 경로의 요청 테넌트를 지우지 않게).
     *
     * @param updateAnchor 전 월이 끝난 뒤 {@code lastPayoutSyncAt} 을 갱신할지 —
     *                     🔴 PLAN 2609_31 D3: {@code month} 를 지정한 호출은 과거 구간 백필이므로 false 다.
     *                     앵커를 찍으면 "최초 실행"이 소진돼 {@code payoutBackfillMonths} 초기 백필이 영영
     *                     돌지 않는다. 매출내역 기간 백필
     *                     ({@code SettlementSyncServiceImpl.runAccount(updateAnchor=false)})과 같은 규칙.
     */
    private PayoutUpsertResult runAccount(MarketplaceAccount account, List<YearMonth> months,
                                          boolean updateAnchor) {
        SettlementSource source = sources.get(account.getPlatform());
        Long previousTenant = TenantContext.get();
        PayoutUpsertResult result = PayoutUpsertResult.empty();
        try {
            TenantContext.set(account.getTenantId());
            LocalPayoutBuilder payoutBuilder = payoutBuilders.get(account.getPlatform());
            if (payoutBuilder != null) {
                // No payout API for this platform: rebuild from the stored lines instead of calling the market
                // (FEATURE_2610_20 / D30). The months do not apply — every stored line is regrouped.
                result = payoutBuilder.rebuild(account);
            } else {
                for (YearMonth month : months) {
                    List<SettlementPayoutDraft> drafts = new ArrayList<>(source.fetchPayouts(account, month));
                    // 🔴 Deterministic processing order (D5-5). A null payout date goes last.
                    drafts.sort(Comparator.comparing(SettlementPayoutDraft::settlementDate,
                            Comparator.nullsLast(Comparator.naturalOrder())));
                    for (SettlementPayoutDraft draft : drafts) {
                        result = result.plus(settlementPayoutUpserter.upsert(account, draft));
                    }
                }
            }
            if (updateAnchor) {
                settlementSyncStatusWriter.writePayoutSyncAt(account.getId());
            }
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
