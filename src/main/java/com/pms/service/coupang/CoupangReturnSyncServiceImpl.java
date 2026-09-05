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
import com.pms.service.claim.ClaimStaleSweeper;
import com.pms.service.claim.ClaimTrackingSlicer;
import com.pms.service.claim.ClaimUpserter;
import com.pms.service.claim.CoupangReturnClaimParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * {@link #trackOpenClaims} 는 그 앞에 반품철회 이력을 한 번 조회해 철회 건을 {@code WITHDRAWN} 으로
 * 종결한다(2609_21/01) — 미완결이 있는 계정만, 회차당 +1 호출.
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
    // 반품철회 이력 조회는 범위 상한이 7일(양끝 포함)이라 클레임 창을 그대로 넘기면 쿠팡이 거절한다.
    private static final int WITHDRAW_MAX_RANGE_DAYS = 6;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;
    private final CoupangReturnClaimParser coupangReturnClaimParser;
    private final ClaimUpserter claimUpserter;
    private final OrderClaimRepository orderClaimRepository;
    private final ClaimStaleSweeper claimStaleSweeper;
    private final ClaimTrackingSlicer claimTrackingSlicer;

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
     * 미완결 클레임 추적(D7) — ① 철회 종결(2609_21/01) → ② STALE 스윕 → ③ 남은 건의 접수일 범위를 창 단위로 조회.
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

        // 철회 종결이 스윕보다 먼저다 — 여기서 빠진 건만큼 추적 슬라이스 대상이 준다.
        List<OrderClaim> alive = closeWithdrawn(account, open);
        List<OrderClaim> remaining = claimStaleSweeper.sweep(account, alive);
        // ⚠️ open.size() 로 두면 철회 종결분이 STALE 집계에 섞인다.
        int staleClosed = alive.size() - remaining.size();
        if (remaining.isEmpty()) {
            return new ClaimTrackingResult(0, staleClosed);      // 미완결 없음 = 쿠팡을 아예 치지 않는다
        }

        String path = returnRequestsPath(account);
        int slices = 0;
        List<SyncWindow> windows = claimTrackingSlicer.slices(remaining,
                coupangProperties.getClaimWindowMaxDays(), coupangProperties.getClaimTrackingMaxSlices());
        for (SyncWindow slice : windows) {
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
     * 철회된 반품 접수를 {@code WITHDRAWN} 으로 종결하고, <b>남은 건만</b> 돌려준다 (2609_21/01).
     *
     * 철회 건은 {@code returnRequests} 목록에서 사라지기만 해서 신호를 못 받고 30일 뒤 STALE 로 떨어진다 —
     * 이력 조회가 그 건을 제때 종결시킨다. 종결분을 리스트에서 빼야 스윕·슬라이스 대상에서도 함께 빠진다.
     *
     * ⚠️ 미완결이 0건이면 쿠팡을 아예 치지 않는다(0건 계정에 매 회차 +1 호출이 붙는 것을 막는다).
     */
    private List<OrderClaim> closeWithdrawn(MarketplaceAccount account, List<OrderClaim> open) {
        if (open.isEmpty()) {
            return open;
        }
        Set<String> withdrawn = collectWithdrawHistory(account, withdrawWindow(account));
        if (withdrawn.isEmpty()) {
            return open;
        }

        List<OrderClaim> alive = new ArrayList<>();
        int closed = 0;
        for (OrderClaim claim : open) {
            if (withdrawn.contains(claim.getExternalClaimId())) {
                claimUpserter.closeAsWithdrawn(claim.getId());
                closed++;
            } else {
                alive.add(claim);
            }
        }
        // ClaimTrackingResult 에 철회 칸이 없다(스키마를 늘리지 않는다) — 이 로그가 유일한 검증 신호다.
        log.info("Claim withdraw closed: account={} open={} closed={}", account.getId(), open.size(), closed);
        return alive;
    }

    /**
     * 철회 이력 조회 창 = 클레임 창을 쿠팡 상한(7일, 양끝 포함)으로 자른 것.
     *
     * 별도 창 설정을 만들지 않는다(튜닝 손잡이가 늘면 어느 값이 무엇을 덮는지 알 수 없게 된다) —
     * 오래 쉰 계정에서 잘려나간 구간의 철회는 놓치지만, 철회 라벨은 있으면 좋은 것이지 없으면 안 되는
     * 것이 아니다(그 건은 기존대로 STALE 로 떨어진다).
     */
    private SyncWindow withdrawWindow(MarketplaceAccount account) {
        SyncWindow window = newClaimWindow(account);
        LocalDate earliest = window.to().minusDays(WITHDRAW_MAX_RANGE_DAYS);
        return window.from().isBefore(earliest) ? new SyncWindow(earliest, window.to()) : window;
    }

    /**
     * 반품철회 이력에서 <b>접수번호만</b> 모은다. 사유·시각·요청자 등은 파싱조차 하지 않는다 —
     * 철회는 "종결됐다"는 사실만 필요하고, 나머지를 저장하면 D19(PII 최소)와 부딪힌다.
     *
     * ⚠️ 이 조회의 실패가 동기화 회차를 깨면 안 된다 — 예외를 삼키고 빈 집합을 돌려준다(경고만).
     * ⚠️ 페이징은 {@code nextToken} 이 아니라 {@code pageIndex}/{@code nextPageIndex} 다(이 API 만의 형태).
     */
    private Set<String> collectWithdrawHistory(MarketplaceAccount account, SyncWindow window) {
        String path = coupangProperties.getReturnWithdrawPath().replace("{vendorId}", account.getVendorId());
        String baseQuery = "dateFrom=" + window.from().format(DATE)
                + "&dateTo=" + window.to().format(DATE)
                + "&sizePerPage=" + MAX_PER_PAGE;

        Set<String> ids = new HashSet<>();
        try {
            String pageIndex = "1";
            int pages = 0;
            do {
                JsonNode parsed = readTree(coupangApiClient.get(path, baseQuery + "&pageIndex=" + pageIndex, account));
                pages++;

                JsonNode rows = parsed.path("data");
                for (JsonNode row : rows) {
                    String receiptId = row.path("cancelId").asText("");
                    if (!receiptId.isBlank()) {
                        ids.add(receiptId);
                    }
                }
                // 실계정 미검증 경로다 — 첫 페이지에 행은 있는데 하나도 못 읽었으면 필드명이 문서와 다른 것이다.
                if (pages == 1 && ids.isEmpty() && rows.isArray() && !rows.isEmpty()) {
                    log.warn("Claim withdraw history: unexpected fields={} (account={})",
                            fieldNames(rows.path(0)), account.getId());
                    return Set.of();
                }

                String prev = pageIndex;
                pageIndex = parsed.path("nextPageIndex").asText("");
                if (pageIndex.equals(prev) || pages >= MAX_PAGES_PER_STATUS) {
                    break;
                }
            } while (!pageIndex.isBlank());
        } catch (Exception e) {
            log.warn("Claim withdraw history query failed: account={}", account.getId(), e);
            return Set.of();
        }
        return ids;
    }

    /** 실측용 — 값은 PII 를 포함할 수 있으므로 <b>필드 이름만</b> 찍는다(D19). */
    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
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
