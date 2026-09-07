package com.pms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 라인 응답 DTO (GET /api/orders).
 *
 * <p>주문 3층(orders → order_shipment → order_line)과 쿠팡 거울(coupang_order_line)을 화면 한 줄로 접는다.
 * raw(JSON 원본)·민감정보는 제외해 목록을 가볍게 유지하고, 파생값 purchasableQty(발주가능수량)를 미리
 * 계산해 노출한다. 고객 <b>이름</b>(주문자·수취인)만 포함한다(FEATURE_2609_06 / D5) — 연락처·주소는 애초에 저장하지 않는다.
 *
 * <p>🔴 {@code status} 는 <b>중립 상태</b>({@code OrderStatus} 이름)다 — 쿠팡 원문은 {@code platformStatus}
 * 로 따로 내려간다(FEATURE_2609_26 / PLAN D4). 라벨은 클라이언트가 중립 값으로 만든다.
 * <p>🔴 {@code effectiveStatus} 는 <b>없앴다</b> — {@code status} 가 전량취소를 {@code CANCELLED} 로
 * 표현한다(PLAN D26, 판정은 서버 소유). {@code cancelled} 는 배지·필터용으로 남긴다.
 *
 * <p>⚠️ 이름은 {@code OrderItemResponse} 를 유지한다 — DTO 이름을 바꾸면 클라이언트 2개의 타입까지
 * 같이 흔들리는데 이 조각의 목적이 아니다.
 * <p>⚠️ 배송비는 담지 않는다 — 박스(order_shipment) 단위라 라인에 복제하면 합계가 중복된다.
 */
@Getter
@AllArgsConstructor
@Builder
public class OrderItemResponse {
    private Long id;
    private Long marketplaceAccountId;
    private String platform;
    private String externalOrderId;
    private String externalBoxId;       // = order_shipment.external_shipment_id (박스 없으면 null)
    private String externalItemId;      // = coupang_order_line.vendor_item_id (옵션 매핑 검증용)
    private String itemName;
    private String ordererName;         // 주문자 이름 (없으면 null)
    private String receiverName;        // 수취인 이름 (없으면 null)
    private int orderCount;
    private int cancelCount;
    private int holdCount;
    private int purchasableQty;         // orderCount-(cancel+hold), 음수 0
    private String status;              // 중립 상태 (PAID·PREPARING·SHIPPED·DELIVERING·DELIVERED·CANCELLED)
    private String platformStatus;      // 플랫폼 원문 (쿠팡 ACCEPT 등) — 거울 행이 없으면 null
    private boolean cancelled;          // 전량 취소 여부 (취소했는데 상품준비중 오표시 방지)
    private LocalDateTime paidAt;       // = orders.ordered_at
    // 주문 시점 금액 스냅샷 (PLAN D9·D10). 과거분은 백필률만큼만 채워지므로 nullable 이다.
    private BigDecimal unitPrice;
    private BigDecimal lineAmount;              // 할인 전 라인 합계
    private BigDecimal discountAmount;          // 총 할인
    private BigDecimal platformDiscountAmount;  // 플랫폼 부담 할인
    // raw(JSON 원본)는 응답에서 제외 — 목록 가벼움 유지
}
