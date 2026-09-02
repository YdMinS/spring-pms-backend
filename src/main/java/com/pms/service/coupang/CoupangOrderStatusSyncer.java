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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 쿠팡 ordersheets 를 상태 1개 단위로 조회·upsert 하는 커밋 경계.
 *
 * ⚠️ {@link #syncStatus} 가 <b>커밋 단위</b>다(PLAN D15). 상태 루프를 한 트랜잭션에 묶으면 뒤쪽 상태의
 * 쿠팡 실패가 앞쪽 상태의 upsert 까지 롤백시켜, 그 계정 주문이 DB 에 한 건도 남지 않는다(2026-09-02 사고).
 * ⚠️ 호출자({@link CoupangOrderSyncServiceImpl})는 트랜잭션 없이 호출해야 한다 — 바깥 트랜잭션이 있으면
 * REQUIRED 로 합류해 경계가 다시 하나로 합쳐진다.
 *
 * ⚠️ 외부 ID(orderId/shipmentBoxId/vendorItemId)는 모두 문자열로 저장한다(external_* = VARCHAR,
 *    옵션 매칭키 platform_item_id 와 타입 일치). 금액·배송정보는 저장하지 않고 raw 에 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangOrderStatusSyncer {

    private static final String PLATFORM_COUPANG = "COUPANG";
    private static final int MAX_PER_PAGE = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KST_OFFSET = "%2B09:00";        // +09:00, URL-encoded (+ → %2B)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CoupangApiClient coupangApiClient;
    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    /**
     * 상태 1개를 nextToken 이 빌 때까지 페이징 조회해 order_item 에 멱등 upsert 한다.
     *
     * account 는 detached 로 들어온다(파사드가 트랜잭션 밖에서 조회) — scalar 필드만 사용하므로 안전하다.
     * TenantContext 는 파사드가 같은 스레드에 세팅해 두므로 이 트랜잭션도 그 테넌트로 열린다.
     */
    @Transactional
    public StatusSyncResult syncStatus(MarketplaceAccount account, CoupangOrderStatus status) {
        String path = coupangProperties.getOrdersheetsPath().replace("{vendorId}", account.getVendorId());
        String baseQuery = baseQuery(status);
        String nextToken = null;

        int newCount = 0;
        int updatedCount = 0;
        int pages = 0;

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

        return new StatusSyncResult(newCount, updatedCount, pages);
    }

    /**
     * 최근 sync-days 의 주문서 기본 쿼리 (지정 status, nextToken 제외).
     * 날짜는 ISO-8601 KST: "yyyy-MM-dd+09:00" (+ 는 %2B 로 인코딩, 서명/전송 동일 문자열 사용).
     */
    private String baseQuery(CoupangOrderStatus status) {
        // 쿠팡 createdAt 필터는 KST 기준 → 서버 TZ(UTC)로 계산하면 KST 자정~오전9시에 만든 주문이
        // 전날로 밀려 창 밖으로 빠진다. 반드시 KST 달력으로 오늘을 계산한다.
        LocalDate to = LocalDate.now(KST);
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

    /** 상태 1개 처리 결과. */
    public record StatusSyncResult(int newCount, int updatedCount, int pages) {
    }
}
