package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Platform;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.coupang.CoupangReturnSyncService;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Coupang claim sync (FEATURE_2609_18 / 06 · D17 · D21) — the exchange load, and the claim screen's claims-only round
 * ({@link #syncClaimsOnly}, FEATURE_2610_23 / D12).
 *
 * Unlike returns, the exchange load is <b>the one path of this feature that adds Coupang calls</b> — it cannot ride on
 * the cancel-correction response, so exchangeRequests is called on its own. Calls per account = 1 new window +
 * tracking slices (cap {@code claim-tracking-max-slices}).
 *
 * <p>⚠️ No class-level {@code @Transactional} — an external HTTP loop. The exchange load writes in the transactions of
 * {@link ClaimUpserter} (REQUIRES_NEW) · {@link ClaimStaleSweeper}; the claims-only round also writes through
 * {@code CoupangReturnSyncService} · {@code ClaimOrderBackfillService} · {@code SyncStatusRecorder} (their own
 * transactions).
 *
 * <p>⚠️ Do not reuse {@code CoupangReturnSyncServiceImpl.collect()} — its {@code applyCancel} would ride on the
 * exchange response (against D16). Keep only the paging shape the same.
 *
 * <p>⚠️ Exchanges have up to 5 slices even in a normal state (STALE 30 days ÷ 7-day width) — unlike the returns' 1.
 * The cap 6 is a margin built on that value, not a "never hit" safety net. When 429 shows up, lower
 * {@code claim-tracking-max-slices} (it cuts the return and exchange slice counts together).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangClaimAdapter implements ClaimSyncAdapter {

    private static final int MAX_PAGES = 20;                // nextToken 무한루프 가드

    /** ⚠️ 반품(yyyy-MM-dd)과 다르다 — 교환 조회는 시각까지 필수다. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;
    private final CoupangExchangeClaimParser coupangExchangeClaimParser;
    private final ClaimUpserter claimUpserter;
    private final OrderClaimRepository orderClaimRepository;
    private final ClaimStaleSweeper claimStaleSweeper;
    private final ClaimTrackingSlicer claimTrackingSlicer;
    /** FEATURE_2610_23 / D12: the claims-only round — returns ride on the cancel correction (2609_18 D15). */
    private final CoupangReturnSyncService coupangReturnSyncService;
    private final ClaimOrderBackfillService claimOrderBackfillService;
    private final SyncStatusRecorder syncStatusRecorder;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    /**
     * The order is the claim part of the Coupang order round (cancel correction → tracking → exchanges → backfill),
     * moved from {@code ClaimSyncServiceImpl} unchanged (FEATURE_2610_23 / D3 · D12).
     *
     * <p>🔴 Cancel-correction and tracking failures propagate (2609_70 D15-1) — the order round swallows a tracking
     * failure because the orders are its subject; here the user pressed [동기화] for the claims only, so a 200 after a
     * failed tracking would tell the screen "just became current". Exchange and backfill failures stay isolated.
     *
     * <p>🔴 No ordersheets or inquiry dependency, and {@link SyncStatusRecorder} only through
     * {@code recordClaimSyncCompleted} — stamping {@code lastSyncStatus} SUCCESS from a claims-only round would make a
     * channel whose orders failed look healthy.
     */
    @Override
    public void syncClaimsOnly(MarketplaceAccount account) {
        // 🔴 신규 반품 적재가 취소 보정 응답에 얹혀 있다(2609_18 D15) — 빼면 반품이 안 들어온다.
        coupangReturnSyncService.syncCancels(account);

        coupangReturnSyncService.trackOpenClaims(account);
        // Stamped only on a round that finished its tracking (the same as the Coupang order round). Moving it would
        // lose the missed window for good.
        syncStatusRecorder.recordClaimSyncCompleted(account.getId());

        // 교환 적재는 백필 앞이다 — 새로 생긴 미연결 claim 을 같은 회차에 처리하려면 백필이 뒤여야 한다.
        try {
            syncExchanges(account);
        } catch (Exception e) {
            // An exchange failure does not break the round — the returns (Stage A) are already loaded (the same call
            // as the Coupang order round).
            log.warn("Exchange claim sync failed (isolated): account={}", account.getId(), e);
        }

        try {
            claimOrderBackfillService.backfill(account);
        } catch (Exception e) {
            // 백필은 정확도 보정이다 — 실패해도 미연결 클레임은 화면에 "주문 미연결" 로 이미 보인다.
            log.warn("Claim order backfill failed (isolated): account={}", account.getId(), e);
        }

        log.info("Claim-only sync: account={}", account.getId());
    }

    @Override
    public ClaimSyncResult syncExchanges(MarketplaceAccount account) {
        int pages = collectNew(account);

        // 적재 → 스윕 → 슬라이스. findOpen 은 회차당 한 번만 친다(05 의 trackOpenClaims 와 같은 모양).
        List<OrderClaim> open = orderClaimRepository.findOpen(
                account.getId(), ClaimType.EXCHANGE, ClaimStatus.closedStatuses());
        List<OrderClaim> remaining = claimStaleSweeper.sweep(account, open);
        int staleClosed = open.size() - remaining.size();
        int slices = remaining.isEmpty() ? 0 : trackSlices(account, remaining);

        log.info("Exchange claim sync: account={} pages={} slices={} staleClosed={}",
                account.getId(), pages, slices, staleClosed);
        return new ClaimSyncResult(pages, slices, staleClosed);
    }

    /**
     * 신규 창 적재 — 창은 {@code SyncWindow.recent(exchangeWindowDays)} 고정이다(PLAN §4).
     *
     * ⚠️ 반품과 달리 {@code lastClaimSyncAt} 으로 넓히지 않는다 — 쿠팡 상한이 7일이라 넓힐 수가 없다.
     * 앵커가 7일보다 오래됐으면 그 구간은 포기하고 경고만 남긴다(추적 슬라이스가 주워 담는다).
     */
    private int collectNew(MarketplaceAccount account) {
        int windowDays = coupangProperties.getExchangeWindowDays();
        warnIfWindowTruncated(account, windowDays);
        return collect(account, SyncWindow.recent(windowDays));
    }

    private void warnIfWindowTruncated(MarketplaceAccount account, int windowDays) {
        if (account.getLastClaimSyncAt() != null
                && account.getLastClaimSyncAt().toLocalDate().isBefore(
                        LocalDate.now(SyncWindow.KST).minusDays(windowDays))) {
            log.warn("Exchange window truncated to {} days: account={} lastClaimSyncAt={}",
                    windowDays, account.getId(), account.getLastClaimSyncAt());
        }
    }

    /**
     * 미완결 추적 (D7·D10) — 계산은 반품과 같은 {@link ClaimTrackingSlicer} 를 폭만 바꿔 쓴다.
     *
     * ⚠️ {@code remaining} 은 이미 스윕을 통과한 목록이다 — 재조회하지 말 것.
     */
    private int trackSlices(MarketplaceAccount account, List<OrderClaim> remaining) {
        List<SyncWindow> windows = claimTrackingSlicer.slices(remaining,
                coupangProperties.getExchangeWindowDays(), coupangProperties.getClaimTrackingMaxSlices());
        int slices = 0;
        for (SyncWindow slice : windows) {
            int pages = collect(account, slice);
            slices++;
            log.info("Exchange tracking slice: account={} from={} to={} pages={}",
                    account.getId(), slice.from(), slice.to(), pages);
        }
        return slices;
    }

    /**
     * 한 창을 nextToken 페이징하며 교환 클레임을 적재한다.
     *
     * ⚠️ 이 메서드는 <b>DB 에 적재</b>하고 페이지 수만 돌려준다 — receipt 가 필요하면
     * {@link #findExchangeReceipt} 를 쓸 것(가시성만 바꿔 부르면 클레임이 재적재된다).
     */
    private int collect(MarketplaceAccount account, SyncWindow window) {
        return pageThrough(account, window, receipt -> {
            ingest(account, receipt);
            return true;
        });
    }

    /**
     * 한 창을 nextToken 페이징하며 receipt 를 방문자에게 넘긴다. 방문자가 {@code false} 를 돌려주면
     * 즉시 멈춘다(다음 페이지를 치지 않는다).
     *
     * <p>창 조립·서명·페이징은 여기 <b>한 벌</b>만 존재한다 — 액션 어댑터(05 X3)가 재조회를 위해
     * 조회 관례를 복사하면 {@code vendorId} 치환·쿼리 비인코딩·{@code nextToken} 처리가 두 벌이 되어
     * 한쪽만 고쳐지는 날이 온다.
     *
     * ⚠️ 쿼리를 인코딩하지 말 것 — 서명 대상과 전송 문자열이 같아야 한다({@code CoupangApiClientImpl}
     * 이 {@code URI.create} 로 그대로 보낸다).
     * ⚠️ status 를 생략해 전 상태를 받는다(PLAN §4) — 상태 루프를 만들면 호출이 배로 는다.
     *
     * @return 실제로 조회한 페이지 수
     */
    int pageThrough(MarketplaceAccount account, SyncWindow window, Predicate<JsonNode> visitor) {
        String path = coupangProperties.getExchangeRequestsPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());
        String baseQuery = "createdAtFrom=" + window.from().atStartOfDay().format(DATE_TIME)
                + "&createdAtTo=" + window.to().atTime(23, 59, 59).format(DATE_TIME)
                + "&maxPerPage=" + coupangProperties.getExchangeMaxPerPage();

        int pages = 0;
        String nextToken = null;
        do {
            String query = (nextToken == null || nextToken.isBlank())
                    ? baseQuery
                    : baseQuery + "&nextToken=" + nextToken;

            JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
            pages++;

            for (JsonNode receipt : parsed.path("data")) {
                if (!visitor.test(receipt)) {
                    return pages;               // 찾았으면 다음 페이지를 치지 않는다
                }
            }
            String prev = nextToken;
            nextToken = parsed.path("nextToken").asText("");
            if (nextToken.equals(prev) || pages >= MAX_PAGES) {
                break;
            }
        } while (!nextToken.isBlank());

        return pages;
    }

    /**
     * 교환 접수 1건을 <b>적재 없이</b> 찾아 돌려준다 (FEATURE_2609_21 / 05 · D12).
     *
     * <p>X3(재발송 송장)의 필수 {@code shipmentBoxId} 는 입고확인 후 새로 생성된 재배송 박스라
     * 우리가 저장한 원 배송번호와 다르다 — 전송 직전에 이 메서드로 다시 읽어 얻는다.
     *
     * 🔴 {@link ClaimUpserter} 를 부르지 않는다. 액션 경로가 클레임을 재적재하면 사용자가 버튼을
     * 누를 때마다 동기화가 도는 셈이 된다.
     */
    Optional<JsonNode> findExchangeReceipt(MarketplaceAccount account, String exchangeId, SyncWindow window) {
        if (exchangeId == null || exchangeId.isBlank()) {
            return Optional.empty();
        }
        String wanted = exchangeId.trim();
        AtomicReference<JsonNode> found = new AtomicReference<>();
        pageThrough(account, window, receipt -> {
            if (wanted.equals(receipt.path("exchangeId").asText())) {
                found.set(receipt);
                return false;
            }
            return true;
        });
        return Optional.ofNullable(found.get());
    }

    /** 교환 클레임 적재 — 한 receipt 의 실패가 나머지 페이지를 멈추지 않도록 삼키고 로그만 남긴다. */
    private void ingest(MarketplaceAccount account, JsonNode receipt) {
        try {
            coupangExchangeClaimParser.parse(receipt)
                    .forEach(record -> claimUpserter.upsert(account, ClaimType.EXCHANGE, record));
        } catch (Exception e) {
            log.warn("Exchange claim ingest failed: account={} exchangeId={}", account.getId(),
                    receipt.path("exchangeId").asText(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 exchangeRequests 응답 파싱 실패", e);
        }
    }
}
