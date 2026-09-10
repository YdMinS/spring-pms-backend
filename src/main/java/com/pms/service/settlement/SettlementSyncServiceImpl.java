package com.pms.service.settlement;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.SettlementSyncResponse;
import com.pms.dto.response.SettlementSyncTargetResponse;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.settlement.SettlementLineUpserter.UpsertResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link SettlementSyncService} 구현 (FEATURE_2609_30 / PLAN D7·D10·D11).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다. DB 쓰기는
 * {@link SettlementLineUpserter}(페이지당 REQUIRES_NEW)와 {@link SettlementSyncStatusWriter} 에서만
 * 일어난다({@code CoupangInquiryAdapter} 와 같은 자세).
 *
 * <p>🔴 한 계정의 실패가 나머지를 중단시키지 않는다. 실패한 계정만 {@code failedAccounts} 로 돌려주고
 * 그 계정의 앵커는 <b>건드리지 않는다</b> — 그래야 다음 회차 창이 저절로 넓어진다.
 */
@Slf4j
@Service
public class SettlementSyncServiceImpl implements SettlementSyncService {

    /** 실패 사유 문구 상한 — 응답 바디를 그대로 싣지 않는다(PII·자격증명). */
    private static final int REASON_MAX = 200;

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final SettlementLineUpserter settlementLineUpserter;
    private final SettlementSyncStatusWriter settlementSyncStatusWriter;
    private final CoupangProperties coupangProperties;
    private final int manualMinIntervalMinutes;
    private final Map<Platform, SettlementSource> sources = new EnumMap<>(Platform.class);

    public SettlementSyncServiceImpl(MarketplaceAccountRepository marketplaceAccountRepository,
                                     SettlementLineUpserter settlementLineUpserter,
                                     SettlementSyncStatusWriter settlementSyncStatusWriter,
                                     CoupangProperties coupangProperties,
                                     List<SettlementSource> sources,
                                     @Value("${oclyx.settlement.manual-min-interval-minutes:10}")
                                     int manualMinIntervalMinutes) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.settlementLineUpserter = settlementLineUpserter;
        this.settlementSyncStatusWriter = settlementSyncStatusWriter;
        this.coupangProperties = coupangProperties;
        this.manualMinIntervalMinutes = manualMinIntervalMinutes;
        sources.forEach(source -> this.sources.put(source.platform(), source));
    }

    @Override
    public SettlementSyncResponse sync(Long sellerId, Long accountId) {
        List<MarketplaceAccount> accounts = resolveAccounts(sellerId, accountId);

        UpsertResult total = UpsertResult.empty();
        List<String> failed = new ArrayList<>();
        int skippedCount = 0;
        LocalDateTime nextAvailableAt = null;

        LocalDate to = LocalDate.now(SyncWindow.KST);
        LocalDate from = to.minusDays(coupangProperties.getRevenueDeltaDays());

        for (MarketplaceAccount account : accounts) {
            LocalDateTime available = nextAvailableAt(account);
            if (available != null) {
                // 우리 쪽 호출 억제 — 화면을 자주 열고 버튼을 연타해도 마켓으로 나가지 않게 한다.
                // CoupangRateLimitGuard(429 쿨다운)와 목적이 다르다. 둘 다 필요하다.
                skippedCount++;
                nextAvailableAt = (nextAvailableAt == null || available.isBefore(nextAvailableAt))
                        ? available : nextAvailableAt;
                continue;
            }
            try {
                total = total.plus(runAccount(account, from, to, true));
            } catch (CoupangRateLimitedException e) {
                // 🔴 PLAN 2609_31 D9 — 쿨다운은 프로세스 전역이라 격리해도 나머지 계정이 전부 같은 예외를 맞는다.
                //    200 + failedAccounts 로 내려가면 프론트가 남은 달을 계속 던져 왕복만 늘고, 재시도 가능 시각
                //    문구가 사용자에게 안 보인다. 429 로 즉시 끊는다.
                throw e;
            } catch (RuntimeException e) {
                log.warn("Settlement revenue sync failed for account={}, isolated and continue",
                        account.getId(), e);
                failed.add(describeFailure(account, e));
            }
        }

        boolean allSkipped = !accounts.isEmpty() && skippedCount == accounts.size();
        return new SettlementSyncResponse(accounts.size(), total.lines(), total.matched(),
                total.unmatched(), total.duplicates(), allSkipped,
                allSkipped ? nextAvailableAt : null, failed);
    }

    @Override
    public SettlementSyncResponse syncPeriod(Long accountId, LocalDate from, LocalDate to) {
        if (accountId == null || from == null || to == null) {
            throw new IllegalArgumentException("계정과 조회 기간(from, to)을 모두 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        MarketplaceAccount account = requireSupportedAccount(accountId);

        List<String> failed = new ArrayList<>();
        UpsertResult total = UpsertResult.empty();
        try {
            // 창 분할(31일 상한)은 어댑터가 한다 — 여기서 400 을 사용자에게 그대로 보여주지 않는다.
            total = runAccount(account, from, to, false);
        } catch (CoupangRateLimitedException e) {
            // 🔴 PLAN 2609_31 D9 — 쿨다운은 프로세스 전역이다. 200 + failedAccounts 로 내려가면 프론트의 월
            //    루프가 남은 달을 계속 던져 왕복만 늘고, 재시도 가능 시각 문구가 사용자에게 안 보인다.
            //    429 로 즉시 끊는다.
            throw e;
        } catch (RuntimeException e) {
            log.warn("Settlement period backfill failed for account={}", account.getId(), e);
            failed.add(describeFailure(account, e));
        }
        return new SettlementSyncResponse(1, total.lines(), total.matched(), total.unmatched(),
                total.duplicates(), false, null, failed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementSyncTargetResponse> targets(Long sellerId) {
        return supported(sellerId == null
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId))
                .stream()
                .map(account -> new SettlementSyncTargetResponse(
                        account.getId(),
                        account.getSeller() == null ? null : account.getSeller().getId(),
                        account.getSeller() == null ? null : account.getSeller().getSellerName(),
                        account.getPlatform().name(),
                        account.getAccountAlias(),
                        account.getLastSettlementSyncAt(),
                        account.getLastPayoutSyncAt(),
                        nextAvailableAt(account)))
                .toList();
    }

    /**
     * 계정 1건 적재. 페이지가 도착할 때마다 커밋하므로 중간 실패에도 앞 페이지는 남는다.
     *
     * @param updateAnchor 전 페이지가 끝난 뒤 {@code lastSettlementSyncAt} 을 갱신할지 —
     *                     기간 백필은 false 다(과거 구간이 최신 앵커가 되면 delta 창이 구멍 난다)
     */
    private UpsertResult runAccount(MarketplaceAccount account, LocalDate from, LocalDate to,
                                    boolean updateAnchor) {
        SettlementSource source = sources.get(account.getPlatform());
        // 비-웹 경로(@Scheduled)는 TenantContext 가 비어 있어 @TenantId INSERT 가 NO_TENANT 로 떨어진다.
        // 웹 경로의 값은 저장→복원한다(계정 루프가 요청 테넌트를 지우지 않게).
        Long previousTenant = TenantContext.get();
        Set<String> seenKeys = new HashSet<>();
        UpsertResult[] holder = {UpsertResult.empty()};
        try {
            TenantContext.set(account.getTenantId());
            source.fetchRevenue(account, from, to,
                    page -> holder[0] = holder[0].plus(
                            settlementLineUpserter.upsertPage(account, page, seenKeys)));

            UpsertResult result = holder[0];
            // 🔴 매칭률은 라인마다가 아니라 회차당 한 줄이다 — 라인별 WARN 은 로그를 못 쓰게 만든다.
            //    PLAN "남는 위험"(미분류 비율·product_listing_option_id 충전율)의 판단 재료가 이 한 줄이다.
            log.info("settlement lines={} matched={} ({}%) unmatched={} duplicates={} account={} window={}~{}",
                    result.lines(), result.matched(), matchRate(result), result.unmatched(),
                    result.duplicates(), account.getId(), from, to);

            if (updateAnchor) {
                settlementSyncStatusWriter.writeRevenueSyncAt(account.getId());
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

    private static String matchRate(UpsertResult result) {
        if (result.lines() == 0) {
            return "0.0";
        }
        return String.format("%.1f", result.matched() * 100.0 / result.lines());
    }

    /** 최소 간격이 아직 안 지났으면 재시도 가능 시각을, 지났으면 null 을 돌려준다. */
    private LocalDateTime nextAvailableAt(MarketplaceAccount account) {
        LocalDateTime last = account.getLastSettlementSyncAt();
        if (last == null) {
            return null;
        }
        LocalDateTime available = last.plusMinutes(manualMinIntervalMinutes);
        return available.isAfter(LocalDateTime.now()) ? available : null;
    }

    private List<MarketplaceAccount> resolveAccounts(Long sellerId, Long accountId) {
        if (accountId != null) {
            return List.of(requireSupportedAccount(accountId));
        }
        return supported(sellerId != null
                ? marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId)
                : marketplaceAccountRepository.findByIsActiveTrue());
    }

    private MarketplaceAccount requireSupportedAccount(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("채널 계정을 찾을 수 없습니다: " + accountId));
        if (!sources.containsKey(account.getPlatform())) {
            throw new IllegalArgumentException("정산 조회를 지원하지 않는 플랫폼입니다: " + account.getPlatform());
        }
        return account;
    }

    /** 어댑터가 있는 플랫폼만 남긴다 — 네이버 계정이 섞여 있어도 조용히 건너뛴다(비-스코프). */
    private List<MarketplaceAccount> supported(List<MarketplaceAccount> accounts) {
        return accounts.stream().filter(account -> sources.containsKey(account.getPlatform())).toList();
    }

    private String describeFailure(MarketplaceAccount account, RuntimeException e) {
        String label = account.getAccountAlias() != null ? account.getAccountAlias() : ("#" + account.getId());
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return label + ": " + (reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason);
    }
}
