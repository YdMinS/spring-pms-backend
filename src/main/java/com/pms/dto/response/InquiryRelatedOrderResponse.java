package com.pms.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문의 상세 우측 패널의 관련 주문 — GET /api/inquiries/{id} (FEATURE_2609_23 / PLAN §6).
 *
 * 주문 조회 API 를 새로 만들지 않고 단건 응답에 실어 보낸다. 프론트가 {@code externalOrderId} 로 주문
 * 목록을 다시 뒤지면 기간 필터에 걸려 못 찾는 건이 생긴다.
 *
 * <p>라인은 <b>같은 주문번호의 모든 라인</b>(합포장 포함)이며 {@code isInquiryLine} 이 이 문의가 걸린
 * 라인 표시용이다. 문의에 주문 연결이 없으면 이 객체 자체가 null 이다.
 *
 * <p>🔴 연락처·주소를 담지 않는다(D13). 이름(주문자·수취인)만 실린다 — 2609_06 의 범위 그대로.
 */
public record InquiryRelatedOrderResponse(
        String externalOrderId,
        LocalDateTime paidAt,
        String ordererName,
        String receiverName,
        List<Line> lines) {

    /**
     * 주문 라인.
     *
     * ⚠️ 필드 이름은 {@link OrderItemResponse} 와 <b>그대로</b> 맞춘다(프론트가 두 벌 매핑을 갖지 않도록).
     * 단 PK 만 {@code id} → {@code orderItemId} 로 바꾼다 — 문의 id 와 섞이지 않게 하려는 것이다.
     */
    public record Line(
            Long orderItemId,
            String itemName,
            int orderCount,
            int cancelCount,
            String effectiveStatus,
            boolean isInquiryLine) {
    }
}
