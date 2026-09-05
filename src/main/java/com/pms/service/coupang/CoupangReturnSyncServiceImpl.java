package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.claim.ClaimUpserter;
import com.pms.service.claim.CoupangReturnClaimParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link CoupangReturnSyncService} 구현 — returnRequests 페이징 조회 후 취소수량 매칭 보정.
 *
 * 두 경로로 취소를 잡는다 — 둘 다 날짜창 배치라 계정당 호출 수는 상수(1 + status 4종)다:
 *  1) 취소 배치(cancelType=CANCEL, status·orderId 제외, createdAt 기준) — 고객 결제취소를 조회.
 *  2) 반품 배치(status=RU/UC/CC/PR, cancelType 생략 = RETURN 기본값) — 판매자 품절취소·고객 출고중지요청은
 *     쿠팡에서 receiptType=RETURN 으로 기록되어 (1)의 cancelType=CANCEL 필터에 안 잡힌다 →
 *     이 경로가 status 4종 날짜창 조회로 그런 취소도 반영한다.
 * 매칭은 (orderId + shipmentBoxId + vendorItemId) 4키. 매칭 안 되면 무시(예외 없음).
 *
 * 같은 응답에서 반품 클레임(order_claim)도 적재한다(FEATURE_2609_18 / D15 — 쿠팡 호출 0건 추가).
 * 적재만으로는 창을 벗어난 뒤의 상태 전이를 놓치므로, {@link #trackOpenClaims} 가 미완결 건의 접수일
 * 범위를 추가로 훑는다(D7) — 계정당 호출은 5 + 슬라이스(상한 claim-tracking-max-slices)다.
 * ⚠️ 취소 보정과 클레임 적재는 서로 다른 관심사다(D16): 적재가 실패해도 취소 보정은 끝나야 하므로
 * 예외를 삼키고(로그만), 적재 자체는 {@link ClaimUpserter} 의 REQUIRES_NEW 트랜잭션에서 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CoupangReturnSyncServiceImpl implements CoupangReturnSyncService {

    private static final int MAX_PER_PAGE = 50;
    private static final int MAX_PAGES_PER_STATUS = 20;     // nextToken 무한루프 가드
    private static final List<String> RETURN_STATUSES = List.of("RU", "UC", "CC", "PR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;
    private final CoupangReturnClaimParser coupangReturnClaimParser;
    private final ClaimUpserter claimUpserter;
    private final OrderClaimRepository orderClaimRepository;

    @Override
    public CancelSyncResult syncCancels(MarketplaceAccount account) {
        CancelSyncResult cancels = syncCancelBatch(account);
        CancelSyncResult returns = syncReturnBatch(account);

        int matchedUpdated = cancels.matchedUpdated() + returns.matchedUpdated();
        int pages = cancels.pages() + returns.pages();
        log.info("Coupang cancel sync done: account={} pages={} matchedUpdated={} (cancel={}, return={})",
                account.getId(), pages, matchedUpdated, cancels.matchedUpdated(), returns.matchedUpdated());
        return new CancelSyncResult(matchedUpdated, pages);
    }

    /** (1) 배치: cancelType=CANCEL 으로 신규 조회 창의 결제취소 조회 (status·orderId 제외). */
    private CancelSyncResult syncCancelBatch(MarketplaceAccount account) {
        String path = returnRequestsPath(account);
        String baseQuery = "cancelType=CANCEL&" + windowQuery(newClaimWindow(account));
        return collect(account, path, baseQuery, Integer.MAX_VALUE);
    }

    /**
     * 신규 조회 창(FEATURE_2609_18 D6). cancel-sync-days 가 하한이라 <b>기존보다 좁아지지 않는다</b> —
     * 취소 보정이 이 창을 공유하기 때문이다(D15·D16). 오래 쉰 계정만 자동으로 넓어진다.
     * 창이 KST 달력 날짜 단위라 D6 의 overlap(6~12h)은 창 자체에 이미 포함돼 있다 — 별도 파라미터를 두지 않는다.
     *
     * ⚠️ 부수효과를 알고 받아들인다: 창은 계정당 5배치(cancelType=CANCEL 1 + status 4종)가 공유하므로,
     * 오래 쉰 계정에서는 취소 보정 조회 창까지 최대 claim-window-max-days 로 넓어진다(페이지 수 증가).
     * D16 은 "취소 보정 <b>로직</b>을 바꾸지 않는다"는 뜻이고, 창은 D15(응답 재사용) 때문에 애초에 공유물이다.
     * 창을 클레임 전용으로 분리하면 같은 구간을 두 번 호출하게 되므로 분리하지 말 것 —
     * 넓어진 회차의 부담이 문제가 되면 claim-window-max-days 를 낮춘다.
     */
    private SyncWindow newClaimWindow(MarketplaceAccount account) {
        int minDays = coupangProperties.getCancelSyncDays();
        // ⚠️ null 분기 필수 — recentSince(null, ...) 은 상한(30일)을 주지만 D6 의 첫 실행은 7일이다.
        return account.getLastClaimSyncAt() == null
                ? SyncWindow.recent(minDays)
                : SyncWindow.recentSince(account.getLastClaimSyncAt(), minDays,
                                         coupangProperties.getClaimWindowMaxDays());
    }

    private String returnRequestsPath(MarketplaceAccount account) {
        return coupangProperties.getReturnrequestsPath().replace("{vendorId}", account.getVendorId());
    }

    /** 조회 창 + 페이지 크기 — 쿼리 문자열의 공통 조각(선행 '&' 없음: 첫 파라미터로도 쓰인다). */
    private String windowQuery(SyncWindow window) {
        return "createdAtFrom=" + window.from().format(DATE)
                + "&createdAtTo=" + window.to().format(DATE)
                + "&maxPerPage=" + MAX_PER_PAGE;
    }

    /**
     * (2) 반품 배치: status 4종을 날짜창으로 조회(cancelType 생략 = RETURN 기본값).
     *
     * 판매자 품절취소·고객 출고중지요청은 receiptType=RETURN 으로 기록돼 (1) 의 cancelType=CANCEL 에
     * 안 잡힌다. 쿠팡 문서상 그 건은 RU(출고중지요청)·UC(반품접수) 에서 조회되고, 동기화 사이에 상태가
     * 넘어갔을 수 있어 CC(반품완료)·PR(쿠팡확인요청) 까지 4종을 모두 훑는다.
     *
     * ⚠️ 주문번호 1건당 1호출(구 reconcilePreShipment)로 되돌리지 말 것 — 2026-09-02 dev 에서 초당
     * 버스트로 HTTP 429 를 유발했다. status 를 지정하면 orderId 없이 날짜창 조회가 되므로 루프는 불필요하다.
     * 조회창은 반품 "접수" 생성시각 기준이라 주문 나이와 무관 → (1) 과 같은 신규 조회 창을 공유한다
     * ({@link #newClaimWindow} — cancelSyncDays 가 하한).
     */
    private CancelSyncResult syncReturnBatch(MarketplaceAccount account) {
        String path = returnRequestsPath(account);
        String window = "&" + windowQuery(newClaimWindow(account));

        int matched = 0;
        int pages = 0;
        for (String status : RETURN_STATUSES) {
            CancelSyncResult r = collect(account, path, "status=" + status + window, MAX_PAGES_PER_STATUS);
            matched += r.matchedUpdated();
            pages += r.pages();
        }
        return new CancelSyncResult(matched, pages);
    }

    /**
     * 미완결 클레임 추적(D7) — ① STALE 스윕 → ② 남은 건의 접수일 범위를 창 단위로 조회.
     *
     * 조회는 신규 조회 창과 같은 {@code collect()} 를 탄다 — {@code applyCancel} 과 클레임 upsert 가
     * 함께 돌지만 <b>둘 다 멱등</b>이라 무해하고, 신규 창에서 놓친 건을 주워 담는 이득이 있다.
     *
     * ⚠️ 슬라이스마다 status 4종을 도는 형태로 만들지 말 것 — 호출이 4배가 된다. status 를 생략하면
     * 전 상태 조회가 된다는 전제가 dev 에서 깨지면, 슬라이스 상한을 낮추고(6→2) 4종 루프로 바꾼다.
     * ⚠️ 상한(D10)에 걸린 분은 다음 회차로 이월되지 않는다 — 커서가 없어 다음 회차도 같은 앞부분을
     * 자른다. 근거는 D11 이다: 미완결이 claim-stale-days 안이면 상한이 아예 안 걸리고, 잘리는 쪽은
     * 항상 최신 구간이라 신규 조회 창이 이미 덮는다. 커서를 도입하지 말 것.
     */
    @Override
    public ClaimTrackingResult trackOpenClaims(MarketplaceAccount account) {
        List<OrderClaim> open = orderClaimRepository.findOpen(
                account.getId(), ClaimType.RETURN, ClaimStatus.closedStatuses());

        List<OrderClaim> remaining = sweepStale(account, open);
        int staleClosed = open.size() - remaining.size();
        if (remaining.isEmpty()) {
            return new ClaimTrackingResult(0, staleClosed);      // 미완결 없음 = 쿠팡을 아예 치지 않는다
        }

        String path = returnRequestsPath(account);
        int slices = 0;
        for (SyncWindow slice : trackingSlices(remaining)) {
            // cancelType 생략 = RETURN 기본값 / status 생략 = 전 상태
            String query = windowQuery(slice);
            CancelSyncResult r = collect(account, path, query, MAX_PAGES_PER_STATUS);
            slices++;
            // Slice results are not surfaced in OrderSyncResult -> this log is the only way to verify D16.
            log.info("Claim tracking slice: account={} from={} to={} pages={} matchedUpdated={}",
                    account.getId(), slice.from(), slice.to(), r.pages(), r.matchedUpdated());
        }
        return new ClaimTrackingResult(slices, staleClosed);
    }

    /**
     * 종결 신호를 영영 못 받는 건이 추적 대상에 영구히 남는 것을 막는다(D11). 강제 종결이지 삭제가 아니다.
     *
     * ⚠️ 벌크 {@code @Modifying} UPDATE 를 쓰지 않는다 — {@code @TenantId} 필터가 JPQL 벌크 갱신에
     * 적용되는지 확신할 수 없다. 건수가 작으므로 로드 후 개별 저장한다.
     *
     * @return 아직 살아 있는(추적 대상) 클레임
     */
    private List<OrderClaim> sweepStale(MarketplaceAccount account, List<OrderClaim> open) {
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

    /**
     * 미완결의 최소 접수일 ~ 오늘(KST) 을 claim-window-max-days 폭으로 자른다(D7·D10).
     *
     * ⚠️ {@code receivedAt} 은 이미 KST 벽시계다 — {@code atZone(...)} 으로 다시 환산하면 9시간 밀린다.
     * 앞(오래된 쪽)부터 claim-tracking-max-slices 개까지만 자른다. 상한 0 = 슬라이스 조회 비활성.
     */
    private List<SyncWindow> trackingSlices(List<OrderClaim> open) {
        int maxSlices = coupangProperties.getClaimTrackingMaxSlices();
        LocalDate oldest = open.stream()
                .map(OrderClaim::getReceivedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .map(LocalDateTime::toLocalDate)
                .orElse(null);
        if (maxSlices <= 0 || oldest == null) {
            return List.of();
        }

        LocalDate today = LocalDate.now(SyncWindow.KST);
        int width = coupangProperties.getClaimWindowMaxDays();
        List<SyncWindow> slices = new ArrayList<>();
        LocalDate from = oldest.isAfter(today) ? today : oldest;
        while (slices.size() < maxSlices) {
            LocalDate to = from.plusDays(width);
            if (to.isAfter(today)) {
                to = today;
            }
            slices.add(new SyncWindow(from, to));
            if (!to.isBefore(today)) {
                break;
            }
            from = to.plusDays(1);
        }
        return slices;
    }

    /** 주어진 returnRequests 쿼리를 nextToken 페이징하며 매칭 취소를 applyCancel 로 반영. */
    private CancelSyncResult collect(MarketplaceAccount account, String path, String baseQuery, int maxPages) {
        int matchedUpdated = 0;
        int pages = 0;
        String nextToken = null;

        do {
            String query = (nextToken == null || nextToken.isBlank())
                    ? baseQuery
                    : baseQuery + "&nextToken=" + nextToken;

            JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
            pages++;

            for (JsonNode receipt : parsed.path("data")) {
                String orderId = receipt.path("orderId").asText();
                for (JsonNode item : receipt.path("returnItems")) {
                    if (applyCancel(account, orderId, item)) {
                        matchedUpdated++;
                    }
                }
                ingestClaims(account, receipt);
            }
            String prev = nextToken;
            nextToken = parsed.path("nextToken").asText("");
            if (nextToken.equals(prev) || pages >= maxPages) {
                break;
            }
        } while (!nextToken.isBlank());

        return new CancelSyncResult(matchedUpdated, pages);
    }

    /**
     * 반품 클레임 적재 — 취소 보정을 깨뜨리면 안 되므로 예외를 삼키고 로그만 남긴다(D16).
     *
     * receiptType 이 RETURN 이 아닌 건은 파서가 걸러낸다(D23) — 단순 결제취소는 cancel_count 보정만.
     */
    private void ingestClaims(MarketplaceAccount account, JsonNode receipt) {
        try {
            coupangReturnClaimParser.parse(receipt)
                    .forEach(record -> claimUpserter.upsert(account, ClaimType.RETURN, record));
        } catch (Exception e) {
            log.warn("Claim ingest failed: account={} receiptId={}", account.getId(),
                    receipt.path("receiptId").asText(), e);
        }
    }

    /**
     * returnItem 1건을 order_item 4키로 매칭해 cancel_count 보정.
     * 다중 취소 접수 합산 여부는 실데이터로 확인 전까지 max 로 단순화(설계 §4). 매칭 없으면 무시.
     *
     * @return 실제로 갱신했으면 true
     */
    private boolean applyCancel(MarketplaceAccount account, String orderId, JsonNode item) {
        String boxId = item.path("shipmentBoxId").asText();
        String vendorItemId = item.path("vendorItemId").asText();
        int cancelCount = item.path("cancelCount").asInt(0);

        Optional<OrderItem> match = orderItemRepository
                .findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                        account.getId(), boxId, orderId, vendorItemId);
        if (match.isEmpty()) {
            return false;
        }

        OrderItem existing = match.get();
        int newCancel = Math.max(existing.getCancelCount(), cancelCount);
        if (newCancel == existing.getCancelCount()) {
            return false;
        }

        orderItemRepository.save(existing.toBuilder().cancelCount(newCancel).build());
        return true;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 returnRequests 응답 파싱 실패", e);
        }
    }
}
