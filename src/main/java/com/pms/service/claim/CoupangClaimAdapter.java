package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 쿠팡 교환 클레임 동기화 (FEATURE_2609_18 / 06 · D17 · D21).
 *
 * 반품과 달리 <b>이 기능에서 유일하게 쿠팡 호출이 느는 경로</b>다 — 취소 보정 응답에 얹을 수 없어
 * exchangeRequests 를 따로 친다. 계정당 호출은 신규 창 1 + 추적 슬라이스(상한
 * {@code claim-tracking-max-slices}) 다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다. DB 쓰기는
 * {@link ClaimUpserter}(REQUIRES_NEW)·{@link ClaimStaleSweeper} 의 트랜잭션에서 일어난다.
 *
 * <p>⚠️ {@code CoupangReturnSyncServiceImpl.collect()} 를 재사용하지 말 것 — 안의 {@code applyCancel}
 * 이 교환 응답에 얹힌다(D16 위반). 페이징 모양만 같게 유지한다.
 *
 * <p>⚠️ 교환은 정상 상태에서도 슬라이스가 최대 5개다(STALE 30일 ÷ 7일 폭) — 반품이 1개인 것과 다르다.
 * 상한 6은 이 값을 전제로 한 여유분이지 "안 걸리는 안전망"이 아니다. 429 가 보이면
 * {@code claim-tracking-max-slices} 를 낮춘다(반품·교환 슬라이스 수를 함께 자른다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangClaimAdapter implements ClaimSyncAdapter {

    private static final String PLATFORM_COUPANG = "COUPANG";
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

    @Override
    public String platform() {
        return PLATFORM_COUPANG;
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
     * ⚠️ 쿼리를 인코딩하지 말 것 — 서명 대상과 전송 문자열이 같아야 한다({@code CoupangApiClientImpl}
     * 이 {@code URI.create} 로 그대로 보낸다).
     * ⚠️ status 를 생략해 전 상태를 받는다(PLAN §4) — 상태 루프를 만들면 호출이 배로 는다.
     */
    private int collect(MarketplaceAccount account, SyncWindow window) {
        String path = coupangProperties.getExchangeRequestsPath()
                .replace("{vendorId}", account.getVendorId());
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
                ingest(account, receipt);
            }
            String prev = nextToken;
            nextToken = parsed.path("nextToken").asText("");
            if (nextToken.equals(prev) || pages >= MAX_PAGES) {
                break;
            }
        } while (!nextToken.isBlank());

        return pages;
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
