package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 계정 × 달 상품매출 (FEATURE_2609_33 / PLAN 2609_33 D4-1 · D11 · D11-1).
 *
 * <p>🔴 <b>고정비 부과 판정 전용</b>이다. 화면 집계({@link SalesLineGroup})와 <b>같은 축·같은 식</b>이어야
 * 한다: 날짜는 {@code order.orderedAt}, 금액은 {@code grossSales − discount}(할인 후·배송비 제외).
 * 식이 갈리면 화면 매출과 판정 근거가 어긋나 사용자가 둘 중 하나를 버그로 읽는다.
 *
 * <p>🔴 판정 매출은 <b>그 달 전체</b>다 — 조회 기간이 달을 잘라도 자르지 않는다(D4-1). 호출부가 걸친
 * 달들의 1일 00:00 ~ 마지막 달 다음 달 1일 00:00 으로 넓혀서 부른다.
 *
 * <p>⚠️ 생성자 projection 이다. {@code year()}/{@code month()} 는 정수, 금액식은 {@code BigDecimal} 로
 * 돌아오므로 필드 타입이 정확히 맞아야 한다.
 *
 * @param netSales {@code Σ(unitPrice × netQty) − Σ(discountAmount × netQty / orderQty)}
 */
public record MonthlyChannelSales(Long accountId, int year, int month, BigDecimal netSales) {
}
