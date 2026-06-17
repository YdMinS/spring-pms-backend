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
import java.util.Optional;

/**
 * {@link CoupangReturnSyncService} 구현 — returnRequests 페이징 조회 후 취소수량 매칭 보정.
 *
 * 결제완료 단계 취소 조회 규칙(실 스펙): cancelType=CANCEL 이면 status·orderId 파라미터 제외,
 * createdAt(접수일) 기준 조회. timeFrame 미사용 → nextToken 페이징 지원.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CoupangReturnSyncServiceImpl implements CoupangReturnSyncService {

    private static final int MAX_PER_PAGE = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public CancelSyncResult syncCancels(MarketplaceAccount account) {
        String path = coupangProperties.getReturnrequestsPath().replace("{vendorId}", account.getVendorId());
        String baseQuery = baseQuery();

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
            nextToken = parsed.path("nextToken").asText("");
        } while (nextToken != null && !nextToken.isBlank());

        log.info("Coupang cancel sync done: account={} pages={} matchedUpdated={}",
                account.getId(), pages, matchedUpdated);
        return new CancelSyncResult(matchedUpdated, pages);
    }

    /** cancelType=CANCEL 으로 최근 cancel-sync-days 의 결제완료 취소 조회 (status·orderId 제외). */
    private String baseQuery() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(coupangProperties.getCancelSyncDays());
        return "cancelType=CANCEL"
                + "&createdAtFrom=" + from.format(DATE)
                + "&createdAtTo=" + to.format(DATE)
                + "&maxPerPage=" + MAX_PER_PAGE;
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
