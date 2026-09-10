package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 판매 내역 한 줄 — <b>주문 라인 그대로</b> (FEATURE_2609_34).
 *
 * <p>채널별 매출 화면이 "이 매출이 어디서 나왔나"에 답하는 목록이다. 위의 상품별 집계
 * ({@link ProductProfitResponse})가 <i>무엇이</i> 팔렸는지를 말한다면, 이쪽은 <i>어느 주문에서</i>
 * 팔렸는지를 말한다 — 사용자가 마켓 관리자 화면과 대조할 수 있는 유일한 축이다.
 *
 * <p>🔴 <b>금액식은 집계 쿼리와 같아야 한다</b>({@code OrderLineRepository.aggregateSales}):
 * {@code netQty = orderQty − cancelQty} · {@code grossSales = unitPrice × netQty} ·
 * {@code discount = discountAmount × netQty / orderQty}. 다르게 계산하면 이 목록의 합이 바로 위
 * 채널 행의 매출액과 어긋나고, 어긋나는 순간 둘 중 어느 쪽이 맞는지 아무도 모른다.
 *
 * <p>⚠️ {@code holdQty}(환불대기)는 유효수량에서 <b>빼지 않는다</b>(D14) — 확정될 때마다 매출이 출렁인다.
 * 표시만 하고 금액에는 넣지 않는다.
 *
 * @param masterProductName 마스터 상품명. 🔴 {@code null} = 채널 옵션에 연결되지 않은 주문이다 —
 *                          <b>목록에서 빼지 않는다</b>. 빼면 합계가 어긋나고 그 이유가 화면에서 사라진다
 * @param itemName          주문 당시 채널이 준 옵션명. 마스터 연결이 없어도 이건 있다
 */
public record SalesLineView(
        Long orderLineId,
        Long accountId,
        LocalDateTime orderedAt,
        String externalOrderId,
        String itemName,
        String masterProductName,
        long orderQty,
        long cancelQty,
        long holdQty,
        long netQty,
        BigDecimal unitPrice,
        BigDecimal grossSales,
        BigDecimal discount) {
}
