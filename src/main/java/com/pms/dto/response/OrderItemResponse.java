package com.pms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 주문 라인 응답 DTO (GET /api/orders).
 *
 * order_item 거울 데이터를 화면이 표시·검증한다. raw(JSON 원본)·민감정보는 제외해 목록을 가볍게 유지하고,
 * 파생값 purchasableQty(발주가능수량)를 미리 계산해 노출한다. external_item_id 는 옵션 매핑 검증용으로 노출.
 * 단, 고객 <b>이름</b>(주문자·수취인)은 화면 식별용으로 포함한다(FEATURE_2609_06 / D5). 연락처·주소는 애초에 저장하지 않는다.
 */
@Getter
@AllArgsConstructor
@Builder
public class OrderItemResponse {
    private Long id;
    private Long marketplaceAccountId;
    private String platform;
    private String externalOrderId;
    private String externalBoxId;
    private String externalItemId;     // = vendorItemId (옵션 매핑 검증용)
    private String itemName;
    private String ordererName;         // 주문자 이름 (없으면 null)
    private String receiverName;        // 수취인 이름 (없으면 null)
    private int orderCount;
    private int cancelCount;
    private int holdCount;
    private int purchasableQty;         // orderCount-(cancel+hold), 음수 0
    private String status;              // 쿠팡 원본 상태 (예: INSTRUCT)
    private String effectiveStatus;     // 전량 취소면 "CANCELLED", 아니면 status — 화면 표시용
    private boolean cancelled;          // 전량 취소 여부 (취소했는데 상품준비중 오표시 방지)
    private LocalDateTime paidAt;
    // raw(JSON 원본)는 응답에서 제외 — 목록 가벼움 유지
}
