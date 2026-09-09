package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 쿠팡 발주서 응답(box + orderItem)을 주문 3층 + 쿠팡 extension 에 멱등 upsert 하는 <b>단일 진입점</b>
 * (FEATURE_2609_26 / PLAN D2·D3·D10. 이전 이름 {@code OrderItemUpserter}).
 *
 * <p>적재 경로는 셋이다 — 정기 동기화 / 송장 접수시트 / 발송처리 폴백. 쿠팡에서 받아온 주문은
 * 경로와 무관하게 여기를 통해 저장된다(PLAN 2609_13 D1). 경로마다 저장 규칙이 갈리면
 * "DB 에 있는가"가 우연이 되고, 발송처리 매칭이 그 우연 위에 서게 된다.
 *
 * <p>박스 1개 처리 순서:
 * <ol>
 *   <li>{@code orders} upsert — 키 (계정, orderId)</li>
 *   <li>{@code order_shipment} upsert — 키 (주문, shipmentBoxId). <b>shipmentBoxId 가 blank 면 행을 만들지 않는다</b></li>
 *   <li>{@code coupang_order_line} 4키 조회 → 있으면 가변 필드만 갱신, 없으면 {@code order_line} + extension 신규</li>
 * </ol>
 *
 * <p>🔴 <b>금액은 최초 insert 에서만 쓴다</b>(PLAN D10) — 기존 라인 갱신 시 금액 4컬럼은 건드리지 않는다.
 * 주문 시점 스냅샷이지 쿠팡의 현재 상태가 아니다.
 * <p>🔴 <b>모르는 박스 상태 = 매핑 실패 = 그 박스 전체 스킵 + WARN</b>(PLAN D7). 임의 기본값을 주지 않는다 —
 * 발송처리 스킵 집합이 블랙리스트라 모르는 상태의 기본 동작이 "전송"이고, 그건 오발송 방향이다.
 * <p>🔴 blank {@code shipmentBoxId}: {@code asText()} 는 값이 없을 때 null 이 아니라 <b>빈 문자열</b>을 준다.
 * 그대로 {@code order_shipment} UNIQUE 에 넣으면 주문당 1행으로 뭉치므로 <b>배송 묶음 행을 만들지 않고</b>
 * 라인의 링크를 null 로 둔다. 반면 {@code coupang_order_line.shipment_box_id} 에는 원문(빈 문자열)을 그대로
 * 저장한다 — 자연키라 null 로 바꾸면 MySQL 이 NULL 중복을 허용해 UNIQUE 가 풀린다.
 *
 * <p>⚠️ {@link #upsertBox} / {@link #upsertBoxes} 가 <b>커밋 단위</b>다(PLAN 2609_13 D3).
 * 호출자는 <b>트랜잭션 없이</b> 불러야 한다 — 바깥 트랜잭션이 있으면 REQUIRED 로 합류해 외부 HTTP 루프
 * 전체가 한 경계로 합쳐진다(2026-09-02 사고).
 * ⚠️ 같은 빈의 public 메서드를 서로 부르지 않는다(프록시 미경유) — 둘 다 private 로 위임한다.
 * ⚠️ 저장 필드는 세 경로가 동일하다. 주소·연락처·배송메시지는 <b>저장하지 않는다</b>(PLAN 2609_13 D7).
 * ⚠️ account 는 detached 로 들어올 수 있다 — scalar 필드({@code getId()})만 사용한다(open-in-view=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderUpserter {

    private static final int NAME_MAX_LENGTH = 100;
    private static final String COUPANG_NONE_TRACKING = "NONE_TRACKING";

    private final OrderRepository orderRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;

    /** box 1개의 orderItems 전부를 upsert. 반환 = (신규, 갱신) 건수. */
    @Transactional
    public UpsertCount upsertBox(MarketplaceAccount account, JsonNode box) {
        return upsertOneBox(account, box);
    }

    /**
     * box 여러 개(= 응답 1페이지)를 upsert. 페이지가 커밋 단위다.
     *
     * ⚠️ 같은 빈의 {@link #upsertBox} 를 부르면 프록시를 안 거쳐 트랜잭션이 안 열린다 —
     *    private {@code upsertOneBox} 를 직접 돈다(둘 다 자기 트랜잭션에서 도는 독립 진입점).
     */
    @Transactional
    public UpsertCount upsertBoxes(MarketplaceAccount account, Iterable<JsonNode> boxes) {
        UpsertCount total = UpsertCount.empty();
        for (JsonNode box : boxes) {
            total = total.plus(upsertOneBox(account, box));
        }
        return total;
    }

    private UpsertCount upsertOneBox(MarketplaceAccount account, JsonNode box) {
        String platformStatus = box.path("status").asText(null);
        Optional<OrderStatus> mapped = OrderStatus.fromCoupang(platformStatus);
        if (mapped.isEmpty()) {
            // 모르는 상태는 저장하지 않는다 — 기본값을 부여하면 오발송 방향으로 조용히 실패한다(D7).
            log.warn("[order-upsert] unknown Coupang status '{}' — box skipped (orderId={}, boxId={})",
                    platformStatus, box.path("orderId").asText(null), box.path("shipmentBoxId").asText(null));
            return UpsertCount.empty();
        }
        OrderStatus status = mapped.get();

        Order order = upsertOrder(account, box);
        OrderShipment shipment = upsertShipment(order, box, platformStatus);

        int newCount = 0;
        int updatedCount = 0;
        for (JsonNode item : box.path("orderItems")) {
            if (upsertLine(account, order, shipment, box, item, status, platformStatus)) {
                newCount++;
            } else {
                updatedCount++;
            }
        }
        return new UpsertCount(newCount, updatedCount);
    }

    /** 주문 헤더 upsert — 키 (계정, orderId). 헤더 값(결제시각·이름)은 박스 레벨에서 온다. */
    private Order upsertOrder(MarketplaceAccount account, JsonNode box) {
        String orderId = box.path("orderId").asText();
        // 쿠팡 시각 파싱의 단일 소유자를 경유한다 — 포맷 목록을 여기 다시 두지 않는다.
        // paidAt 은 오프셋 포함 ISO-8601 이고, 파싱 실패는 null(참고/정렬용, 필터 아님).
        LocalDateTime orderedAt = CoupangTimestamps.parse(box.path("paidAt").asText(null));
        String ordererName = trimName(box.path("orderer").path("name").asText(null));
        String receiverName = trimName(box.path("receiver").path("name").asText(null));

        return orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(account.getId(), orderId)
                .map(existing -> orderRepository.save(existing.toBuilder()
                        .orderedAt(orderedAt)
                        .ordererName(ordererName)
                        .receiverName(receiverName)
                        .build()))
                .orElseGet(() -> orderRepository.save(Order.builder()
                        .marketplaceAccount(account)
                        .platform(Platform.COUPANG)
                        .externalOrderId(orderId)
                        .orderedAt(orderedAt)
                        .ordererName(ordererName)
                        .receiverName(receiverName)
                        .build()));
    }

    /**
     * 배송 묶음 upsert — 키 (주문, shipmentBoxId).
     *
     * <p>🔴 shipmentBoxId 가 blank 면 <b>행을 만들지 않고 null 을 반환</b>한다: 배송비가 박스에 딸린 값이라
     * 박스가 없으면 담을 자리도 없고, 빈 문자열을 UNIQUE 에 넣으면 주문당 1행으로 뭉친다.
     */
    private OrderShipment upsertShipment(Order order, JsonNode box, String platformStatus) {
        String boxId = box.path("shipmentBoxId").asText(null);
        if (boxId == null || boxId.isBlank()) {
            return null;
        }
        BigDecimal shippingFee = CoupangMoney.parse(box.get("shippingPrice"));
        BigDecimal remoteFee = CoupangMoney.parse(box.get("remotePrice"));
        // 추적 가능 여부는 상태가 아니라 배송 묶음의 속성이다 — NONE_TRACKING(업체 직접배송)만 false.
        boolean trackingAvailable = !COUPANG_NONE_TRACKING.equalsIgnoreCase(platformStatus);

        return orderShipmentRepository.findByOrder_IdAndExternalShipmentId(order.getId(), boxId)
                .map(existing -> orderShipmentRepository.save(existing.toBuilder()
                        .shippingFee(shippingFee)
                        .remoteFee(remoteFee)
                        .trackingAvailable(trackingAvailable)
                        .build()))
                .orElseGet(() -> orderShipmentRepository.save(OrderShipment.builder()
                        .order(order)
                        .externalShipmentId(boxId)
                        .shippingFee(shippingFee)
                        .remoteFee(remoteFee)
                        .trackingAvailable(trackingAvailable)
                        .build()));
    }

    /**
     * 주문 라인 멱등 upsert. 신규면 insert(true), 기존이면 가변 필드만 갱신(false) 반환.
     *
     * <p>🔴 기존 라인의 금액 4컬럼은 건드리지 않는다(D10 — 주문 시점 스냅샷).
     */
    private boolean upsertLine(MarketplaceAccount account, Order order, OrderShipment shipment,
                               JsonNode box, JsonNode item, OrderStatus status, String platformStatus) {
        String orderId = box.path("orderId").asText();
        // 자연키 원문은 쿠팡이 준 그대로 저장한다 — 값이 없으면 빈 문자열(""). null 로 바꾸면
        // ① MySQL 이 NULL 중복을 허용해 UNIQUE 가 풀리고 ② 백필 행(external_box_id = '')과 키가 어긋난다.
        String boxIdRaw = box.path("shipmentBoxId").asText();
        String vendorItemId = item.path("vendorItemId").asText();

        int shippingCount = item.path("shippingCount").asInt(0);
        int cancelCount = item.path("cancelCount").asInt(0);
        int holdCount = item.path("holdCountForCancel").asInt(0);
        String itemName = item.path("vendorItemName").asText(null);
        String rawJson = item.toString();

        Optional<CoupangOrderLine> existing = coupangOrderLineRepository
                .findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(
                        account.getId(), boxIdRaw, orderId, vendorItemId);

        if (existing.isPresent()) {
            CoupangOrderLine mirror = existing.get();
            OrderLine line = mirror.getOrderLine();
            orderLineRepository.save(line.toBuilder()
                    .status(status)
                    .orderQty(shippingCount)
                    .cancelQty(cancelCount)
                    .holdQty(holdCount)
                    .itemName(itemName)
                    // 🔴 비어 있을 때만 채운다(D15) — 재동기화가 덮으면 WING 수정으로 깨진 매칭이
                    //    멀쩡한 값을 밀어낸다. 백필이 못 채운 과거 라인을 여기서 주워 담는 경로이기도 하다.
                    .productListingOption(line.getProductListingOption() != null
                            ? line.getProductListingOption()
                            : resolveListingOption(vendorItemId))
                    .build());
            coupangOrderLineRepository.save(mirror.toBuilder()
                    .platformStatus(platformStatus)
                    .raw(rawJson)
                    .build());
            return false;
        }

        OrderLine line = orderLineRepository.save(OrderLine.builder()
                .order(order)
                .orderShipment(shipment)
                .status(status)
                .itemName(itemName)
                .orderQty(shippingCount)
                .cancelQty(cancelCount)
                .holdQty(holdCount)
                // 금액 스냅샷 = 최초 적재에서만. 컬럼명은 중립이고 쿠팡 필드명을 쓰지 않는다(D9).
                .unitPrice(CoupangMoney.parse(item.get("salesPrice")))
                .lineAmount(CoupangMoney.parse(item.get("orderPrice")))
                .discountAmount(CoupangMoney.parse(item.get("discountPrice")))
                .platformDiscountAmount(CoupangMoney.parse(item.get("coupangDiscount")))
                // 중립 링크는 플랫폼 어댑터가 해석한다 — 하류(BOM 전개·재고)는 거울 행을 보지 않는다(D15).
                .productListingOption(resolveListingOption(vendorItemId))
                .build());

        coupangOrderLineRepository.save(CoupangOrderLine.builder()
                .orderLine(line)
                .marketplaceAccount(account)
                .shipmentBoxId(boxIdRaw)
                .orderIdRaw(orderId)
                .vendorItemId(vendorItemId)
                .platformStatus(platformStatus)
                .raw(rawJson)
                .build());
        return true;
    }

    /**
     * vendorItemId → 채널 옵션(FEATURE_2609_28 / PLAN D15). 못 찾으면 <b>null 로 두고 계속 진행</b>한다.
     *
     * <p>⚠️ 매칭 실패를 예외로 만들지 않는다 — 주문 동기화 전체가 멈춘다. 미매핑은 구매목록의
     * {@code unmapped} 축과 출고 화면의 {@code unexpanded} 축이 이미 사람에게 보여준다.
     */
    private ProductListingOption resolveListingOption(String vendorItemId) {
        if (vendorItemId == null || vendorItemId.isBlank()) {
            return null;
        }
        return productListingOptionRepository.findByPlatformOptionId(vendorItemId).orElse(null);
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
