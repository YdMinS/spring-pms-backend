package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * {@link CoupangOrderSyncService} 구현 — ordersheets 페이징 조회 후 order_item upsert.
 *
 * 호출 흐름: {@link CoupangApiClient#get} 으로 서명된 GET → {@link ObjectMapper} 로 파싱 →
 * shipmentBox×orderItem 단위로 {@link #upsert} 멱등 적재. nextToken 이 빌 때까지 페이징한다.
 *
 * ⚠️ 외부 ID(orderId/shipmentBoxId/vendorItemId)는 모두 문자열로 저장한다(external_* = VARCHAR,
 *    옵션 매칭키 platform_item_id 와 타입 일치). 금액·배송정보는 저장하지 않고 raw 에 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CoupangOrderSyncServiceImpl implements CoupangOrderSyncService {

    private static final String PLATFORM_COUPANG = "COUPANG";
    // 전체 상태를 상태별로 조회한다. status 는 단일값 파라미터라, 한 상태만 조회하면 주문이
    // 다음 단계로 넘어갔을 때(예: ACCEPT→INSTRUCT) 그 필터에 안 잡혀 status 갱신이 누락된다.
    // 모든 상태를 돌면 박스 누락 없이 현재 상태가 항상 최신으로 반영된다.
    private static final List<CoupangOrderStatus> SYNC_STATUSES = List.of(CoupangOrderStatus.values());
    private static final int MAX_PER_PAGE = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KST_OFFSET = "%2B09:00";        // +09:00, URL-encoded (+ → %2B)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public SyncResult syncAll() {
        SyncResult total = SyncResult.empty();
        for (MarketplaceAccount account : marketplaceAccountRepository.findByIsActiveTrue()) {
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                continue;
            }
            total = total.plus(syncAccount(account));
        }
        return total;
    }

    @Override
    public SyncResult syncAccount(MarketplaceAccount account) {
        String path = coupangProperties.getOrdersheetsPath().replace("{vendorId}", account.getVendorId());

        int newCount = 0;
        int updatedCount = 0;
        int pages = 0;

        // status 는 단일값 파라미터 → 대상 상태별로 각각 페이징 조회. upsert 는 멱등이라 중복 안전.
        for (CoupangOrderStatus status : SYNC_STATUSES) {
            String baseQuery = baseQuery(status);
            String nextToken = null;

            do {
                String query = (nextToken == null || nextToken.isBlank())
                        ? baseQuery
                        : baseQuery + "&nextToken=" + nextToken;

                JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
                pages++;

                for (JsonNode box : parsed.path("data")) {
                    for (JsonNode item : box.path("orderItems")) {
                        if (upsert(account, box, item)) {
                            newCount++;
                        } else {
                            updatedCount++;
                        }
                    }
                }
                nextToken = parsed.path("nextToken").asText("");
            } while (nextToken != null && !nextToken.isBlank());
        }

        log.info("Coupang sync done: account={} statuses={} pages={} new={} updated={}",
                account.getId(), SYNC_STATUSES, pages, newCount, updatedCount);
        return new SyncResult(newCount, updatedCount, pages);
    }

    /**
     * 최근 sync-days 의 주문서 기본 쿼리 (지정 status, nextToken 제외).
     * 날짜는 ISO-8601 KST: "yyyy-MM-dd+09:00" (+ 는 %2B 로 인코딩, 서명/전송 동일 문자열 사용).
     */
    private String baseQuery(CoupangOrderStatus status) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(coupangProperties.getSyncDays());
        return "createdAtFrom=" + from.format(DATE) + KST_OFFSET
                + "&createdAtTo=" + to.format(DATE) + KST_OFFSET
                + "&status=" + status.name()
                + "&maxPerPage=" + MAX_PER_PAGE;
    }

    /**
     * 주문 라인 멱등 upsert. 신규면 insert(true), 기존이면 가변 필드만 갱신(false) 반환.
     */
    private boolean upsert(MarketplaceAccount account, JsonNode box, JsonNode item) {
        String orderId = box.path("orderId").asText();
        String boxId = box.path("shipmentBoxId").asText();
        String vendorItemId = item.path("vendorItemId").asText();

        int shippingCount = item.path("shippingCount").asInt(0);
        int cancelCount = item.path("cancelCount").asInt(0);
        int holdCount = item.path("holdCountForCancel").asInt(0);
        String status = box.path("status").asText();
        LocalDateTime paidAt = parseDateTime(box.path("paidAt").asText(null));
        String itemName = item.path("vendorItemName").asText(null);
        String rawJson = item.toString();

        Optional<OrderItem> existing = orderItemRepository
                .findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                        account.getId(), boxId, orderId, vendorItemId);

        if (existing.isPresent()) {
            // 기존 줄: 가변 필드만 갱신 (status·취소수량·수량·raw) — toBuilder 로 불변성 유지
            OrderItem updated = existing.get().toBuilder()
                    .orderCount(shippingCount)
                    .cancelCount(cancelCount)
                    .holdCount(holdCount)
                    .status(status)
                    .paidAt(paidAt)
                    .itemName(itemName)
                    .raw(rawJson)
                    .build();
            orderItemRepository.save(updated);
            return false;
        }

        orderItemRepository.save(OrderItem.builder()
                .marketplaceAccount(account)
                .platform(PLATFORM_COUPANG)
                .externalOrderId(orderId)
                .externalBoxId(boxId)
                .externalItemId(vendorItemId)
                .orderCount(shippingCount)
                .cancelCount(cancelCount)
                .holdCount(holdCount)
                .status(status)
                .paidAt(paidAt)
                .itemName(itemName)
                .raw(rawJson)
                .build());
        return true;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 ordersheets 응답 파싱 실패", e);
        }
    }

    /**
     * 쿠팡 paidAt 파싱. 응답은 오프셋 포함 ISO-8601 (예: 2025-01-15T14:17:13.973885-08:00) →
     * KST 로컬시각으로 환산. 파싱 실패 시 null (paidAt 은 참고/정렬용, 필터 아님).
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(KST).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
