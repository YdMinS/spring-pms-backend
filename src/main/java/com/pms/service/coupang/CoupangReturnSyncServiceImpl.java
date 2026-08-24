package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * {@link CoupangReturnSyncService} 구현 — returnRequests 페이징 조회 후 취소수량 매칭 보정.
 *
 * 두 경로로 취소를 잡는다:
 *  1) 배치(cancelType=CANCEL, status·orderId 제외, createdAt 기준) — 고객 결제취소를 효율적으로 조회.
 *  2) 재조정(orderId 단위, 필터 없음) — 발송전(ACCEPT/INSTRUCT) 주문에 대해 orderId 로 직접 조회.
 *     판매자 품절취소(출고중지)는 쿠팡에서 receiptType=RETURN 으로 기록되어 (1)의 cancelType=CANCEL
 *     필터에 안 잡힌다 → 이 경로가 전체 타입을 조회해 그런 취소도 반영한다.
 * 매칭은 (orderId + shipmentBoxId + vendorItemId) 4키. 매칭 안 되면 무시(예외 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CoupangReturnSyncServiceImpl implements CoupangReturnSyncService {

    private static final int MAX_PER_PAGE = 50;
    private static final int MAX_PAGES_PER_ORDER = 20;      // orderId 단위 조회 무한루프 가드
    private static final int RECON_LOOKBACK_DAYS = 30;      // 재조정 조회창(쿠팡 상한 < 31일)
    // 아직 발송 전이라 취소가 가능한 상태들 — 재조정 대상.
    private static final List<String> PRE_SHIPMENT_STATUSES = List.of("ACCEPT", "INSTRUCT");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public CancelSyncResult syncCancels(MarketplaceAccount account) {
        CancelSyncResult batch = syncCancelBatch(account);
        CancelSyncResult recon = reconcilePreShipment(account);

        int matchedUpdated = batch.matchedUpdated() + recon.matchedUpdated();
        int pages = batch.pages() + recon.pages();
        log.info("Coupang cancel sync done: account={} pages={} matchedUpdated={} (batch={}, recon={})",
                account.getId(), pages, matchedUpdated, batch.matchedUpdated(), recon.matchedUpdated());
        return new CancelSyncResult(matchedUpdated, pages);
    }

    /** (1) 배치: cancelType=CANCEL 으로 최근 cancel-sync-days 의 결제취소 조회 (status·orderId 제외). */
    private CancelSyncResult syncCancelBatch(MarketplaceAccount account) {
        String path = coupangProperties.getReturnrequestsPath().replace("{vendorId}", account.getVendorId());
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(coupangProperties.getCancelSyncDays());
        String baseQuery = "cancelType=CANCEL"
                + "&createdAtFrom=" + from.format(DATE)
                + "&createdAtTo=" + to.format(DATE)
                + "&maxPerPage=" + MAX_PER_PAGE;
        return collect(account, path, baseQuery, Integer.MAX_VALUE);
    }

    /**
     * (2) 재조정: 발송전(ACCEPT/INSTRUCT) 주문번호마다 returnRequests?orderId= (전체 타입) 조회.
     * cancelType=CANCEL 배치가 놓치는 판매자 품절취소(receiptType=RETURN)를 잡아 cancel_count 를 보정한다.
     */
    private CancelSyncResult reconcilePreShipment(MarketplaceAccount account) {
        List<String> orderIds = orderItemRepository
                .findDistinctExternalOrderIdByAccountAndStatusIn(account.getId(), PRE_SHIPMENT_STATUSES);
        if (orderIds.isEmpty()) {
            return CancelSyncResult.empty();
        }

        String path = coupangProperties.getReturnrequestsPath().replace("{vendorId}", account.getVendorId());
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(RECON_LOOKBACK_DAYS);
        String window = "&createdAtFrom=" + from.format(DATE)
                + "&createdAtTo=" + to.format(DATE)
                + "&maxPerPage=" + MAX_PER_PAGE;

        int matched = 0;
        int pages = 0;
        for (String orderId : orderIds) {
            CancelSyncResult r = collect(account, path, "orderId=" + orderId + window, MAX_PAGES_PER_ORDER);
            matched += r.matchedUpdated();
            pages += r.pages();
        }
        return new CancelSyncResult(matched, pages);
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
