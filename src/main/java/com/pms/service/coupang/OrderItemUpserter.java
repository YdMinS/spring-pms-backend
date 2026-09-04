package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 쿠팡 발주서 응답(box + orderItem)을 order_item 에 멱등 upsert 하는 단일 진입점.
 *
 * 적재 경로는 셋이다 — 정기 동기화 / 송장 접수시트 / 발송처리 폴백. 쿠팡에서 받아온 주문은
 * 경로와 무관하게 여기를 통해 저장된다(PLAN 2609_13 D1). 경로마다 저장 규칙이 갈리면
 * "DB 에 있는가"가 우연이 되고, 발송처리 매칭이 그 우연 위에 서게 된다.
 *
 * ⚠️ {@link #upsertBox} 가 커밋 단위다(PLAN 2609_13 D3). 호출자는 <b>트랜잭션 없이</b> 불러야 한다 —
 *    바깥 트랜잭션이 있으면 REQUIRED 로 합류해 외부 HTTP 루프 전체가 한 경계로 합쳐진다(2026-09-02 사고).
 * ⚠️ 저장 필드는 동기화와 동일하다. 주소·연락처·배송메시지는 <b>저장하지 않는다</b>(PLAN 2609_13 D7).
 * ⚠️ account 는 detached 로 들어올 수 있다 — scalar 필드({@code getId()})만 사용한다(open-in-view=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderItemUpserter {

    private static final String PLATFORM_COUPANG = "COUPANG";
    private static final int NAME_MAX_LENGTH = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OrderItemRepository orderItemRepository;

    /** box 1개의 orderItems 전부를 upsert. 반환 = (신규, 갱신) 건수. */
    @Transactional
    public UpsertCount upsertBox(MarketplaceAccount account, JsonNode box) {
        int newCount = 0;
        int updatedCount = 0;
        for (JsonNode item : box.path("orderItems")) {
            if (upsertLine(account, box, item)) {
                newCount++;
            } else {
                updatedCount++;
            }
        }
        return new UpsertCount(newCount, updatedCount);
    }

    /**
     * box 여러 개(= 응답 1페이지)를 upsert. 페이지가 커밋 단위다.
     *
     * ⚠️ 같은 빈의 {@link #upsertBox} 를 부르면 프록시를 안 거쳐 트랜잭션이 안 열린다 —
     *    private {@code upsertLine} 을 직접 돈다(둘 다 자기 트랜잭션에서 도는 독립 진입점).
     */
    @Transactional
    public UpsertCount upsertBoxes(MarketplaceAccount account, Iterable<JsonNode> boxes) {
        int newCount = 0;
        int updatedCount = 0;
        for (JsonNode box : boxes) {
            for (JsonNode item : box.path("orderItems")) {
                if (upsertLine(account, box, item)) {
                    newCount++;
                } else {
                    updatedCount++;
                }
            }
        }
        return new UpsertCount(newCount, updatedCount);
    }

    /**
     * 주문 라인 멱등 upsert. 신규면 insert(true), 기존이면 가변 필드만 갱신(false) 반환.
     */
    private boolean upsertLine(MarketplaceAccount account, JsonNode box, JsonNode item) {
        String orderId = box.path("orderId").asText();
        String boxId = box.path("shipmentBoxId").asText();
        String vendorItemId = item.path("vendorItemId").asText();

        int shippingCount = item.path("shippingCount").asInt(0);
        int cancelCount = item.path("cancelCount").asInt(0);
        int holdCount = item.path("holdCountForCancel").asInt(0);
        String status = box.path("status").asText();
        LocalDateTime paidAt = parseDateTime(box.path("paidAt").asText(null));
        String itemName = item.path("vendorItemName").asText(null);
        // orderer/receiver live on the shipmentBox, not on the order line.
        String ordererName = trimName(box.path("orderer").path("name").asText(null));
        String receiverName = trimName(box.path("receiver").path("name").asText(null));
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
                    .ordererName(ordererName)
                    .receiverName(receiverName)
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
                .ordererName(ordererName)
                .receiverName(receiverName)
                .raw(rawJson)
                .build());
        return true;
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

    /** 컬럼 상한(100자)을 넘는 이름은 잘라서 저장한다 — MySQL 은 초과 시 INSERT 자체가 실패한다. */
    private String trimName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= NAME_MAX_LENGTH ? value : value.substring(0, NAME_MAX_LENGTH);
    }

    /** upsert 결과 건수. */
    public record UpsertCount(int newCount, int updatedCount) {
        public static UpsertCount empty() {
            return new UpsertCount(0, 0);
        }

        public UpsertCount plus(UpsertCount o) {
            return new UpsertCount(newCount + o.newCount, updatedCount + o.updatedCount);
        }
    }
}
