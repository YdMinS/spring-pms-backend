package com.pms.dto.response;

import com.pms.domain.Platform;

import java.time.LocalDateTime;

/**
 * 결제완료 주문 한 건의 알림 집계 행 (FEATURE_2609_51 / PLAN D5·D12).
 *
 * <p>🔴 <b>라인이 아니라 주문 단위</b>다 — {@code OrderLineRepository.findNewOrderAlerts} 의 생성자
 * 표현식이 채운다. 라인을 전부 읽어 메모리에서 묶으면 "주문 50건"이라는 페이지 크기 자체가 성립하지 않는다.
 *
 * <p>⚠️ 생성자 projection 이라 <b>타입이 쿼리와 정확히 맞아야 한다</b>: {@code o.platform} 은
 * {@link Platform} enum({@code String} 이 아니다), {@code count(l)} 은 {@code Long},
 * {@code min(l.itemName)} 은 {@code String}. 응답 DTO 의 {@code String platform} 으로 바꾸는 것은
 * <b>서비스의 매핑 단계</b>다 — 여기서 바꾸면 생성자 표현식이 안 맞아 런타임에 터진다.
 *
 * @param itemName  대표 상품명 = {@code min(itemName)}. "첫 라인"은 JPQL 로 표현할 수 없고 화면은 대표
 *                  한 줄과 {@code 상품 N개} 만 보여주므로 어느 줄이든 무방하다
 * @param itemCount 그 주문의 상품(라인) 수
 */
public record NewOrderAlertRow(
        Long orderId,
        String externalOrderId,
        LocalDateTime orderedAt,
        Platform platform,
        String sellerName,
        String itemName,
        Long itemCount) {
}
