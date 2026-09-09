package com.pms.dto.response;

import java.util.List;

/**
 * 구성품(Product) 단위 집계 한 줄. remainingQty = neededQty − purchasedQty.
 *
 * <p>🔴 세 숫자는 모두 <b>전체 기준</b>이다 — 판매자로 쪼개지 않는다(PLAN 2609_29 D6).
 * 쪼개면 "얼마를 더 사야 하나"가 판매자 수만큼 흩어진다. 귀속이 어긋나면 재고 이관(별건)으로 맞춘다.
 *
 * <p>구매목록 탭은 remainingQty &gt; 0, 완료 탭은 remainingQty &lt;= 0 인 그룹만 담는다 — 응답 타입은 같다.
 * lines 는 그 product 에 기여한 모든 라인(주문 + 수동).
 */
public record PurchaseProductGroup(
        Long productId,
        String productName,
        int neededQty,
        int purchasedQty,
        int remainingQty,
        List<PurchaseLine> lines
) {}
