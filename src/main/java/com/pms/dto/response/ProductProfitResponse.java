package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 상품별 수익성 한 줄 (FEATURE_2609_30 / 03 ③).
 *
 * <p>집계 경로는 하나고 <b>그룹 키만 바뀐다</b>:
 * <pre>
 *   crossChannel=true   그룹 = master_product                        "이 상품이 전체적으로 돈이 되나"
 *   crossChannel=false  그룹 = master_product × marketplace_account  "쿠팡은 남는데 네이버는 안 남는다"
 * </pre>
 *
 * <p>⚠️ {@code product_listing_option_id} 가 null 인 라인(백필 누락·WING 수정분)은 <b>버리지 않는다</b> —
 * {@code uncategorized=true} 인 한 행에 모은다. 여기서 버리면 이 목록의 합계가 판매자 요약(①)과 어긋나고,
 * 합계가 어긋나는 순간 화면 전체가 신뢰를 잃는다.
 *
 * @param masterProductId  {@code uncategorized=true} 면 null
 * @param accountId        {@code crossChannel=true} 면 null (별칭도 함께 null)
 */
public record ProductProfitResponse(
        Long masterProductId,
        String masterProductName,
        Long accountId,
        String accountAlias,
        long netQty,
        BigDecimal grossSales,
        BigDecimal discount,
        BigDecimal estFee,
        BigDecimal estNetProfit,
        boolean costBasisReady,
        boolean uncategorized) {
}
