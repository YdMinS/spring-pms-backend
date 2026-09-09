package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 매출 집계의 최소 단위 한 줄 — <b>계정 × 채널 옵션</b> (FEATURE_2609_30 / PLAN D14 · 03 Step 3).
 *
 * <p>🔴 <b>라인을 전부 로드해 자바에서 더하지 않는다.</b> 수량 가중 합(유효수량 비례 안분 포함)은 전부
 * DB 가 하고, 여기 실려 오는 것은 이미 접힌 값이다. 행 수는 주문량이 아니라 <b>팔린 옵션 수</b>에 비례하므로
 * 주문이 늘어도 리포트 비용이 같이 늘지 않는다.
 *
 * <p>세율(수수료·부가세)·배송비·상자비는 여기 없다 — 종류가 적어 서비스가 일괄 로드 후 메모리에서 곱한다
 * (셀마다 resolve 하면 즉시 N+1 이다).
 *
 * <p>⚠️ 생성자 projection 이다. JPQL {@code sum(정수식)} 은 {@code Long}, {@code sum(소수식)} 은
 * {@code BigDecimal} 로 돌아오므로 필드 타입이 정확히 맞아야 한다 — 어긋나면 런타임에 터진다.
 *
 * @param listingOptionId   null = 채널 옵션 연결이 없는 라인(백필 누락·WING 수정분). 버리지 않고 `미분류` 로 모은다
 * @param masterProductId   null = 위와 같음(마스터까지 못 올라간다)
 * @param netQty            {@code Σ (orderQty − cancelQty)}. 🔴 {@code holdQty}(환불대기)는 빼지 않는다(D14)
 * @param holdQty           별도 표기용 합계 — 화면이 "환불대기 12건" 을 보여주는 데 쓴다
 * @param grossSales        {@code Σ (unitPrice × netQty)} — <b>할인 전</b>이다
 * @param discount          {@code Σ (discountAmount × netQty / orderQty)} — 유효수량 비례 안분
 * @param costAmount        {@code Σ (costAmount × netQty / orderQty)} — 원가 스냅샷(changeset 081)만 쓴다
 * @param missingCostLines  스냅샷이 없는 <b>유효수량이 남은</b> 라인 수. 0 이 아니면 순이익을 내지 않는다
 */
public record SalesLineGroup(
        Long sellerId,
        String sellerName,
        Long accountId,
        Long listingOptionId,
        Long masterProductId,
        String masterProductName,
        long netQty,
        long holdQty,
        BigDecimal grossSales,
        BigDecimal discount,
        BigDecimal costAmount,
        long missingCostLines) {
}
