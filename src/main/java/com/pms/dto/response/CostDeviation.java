package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 기준가와 최근 매입가의 괴리 1건 (FEATURE_2609_28 / PLAN §5 대사 신호).
 *
 * <p>용도는 <b>"이 상품들 기준가가 낡았다 → 갱신할까?"를 사람이 판단하게 하는 것</b>이다.
 * 이 목록을 보고 자동으로 갱신하지 않는다.
 *
 * <p>⚠️ 프로모션 매입({@code reflectToBasePrice = false})은 대상이 아니다 — 애초에 기준가를 안 건드렸으니
 * 차이가 나는 것이 정상이고, 괴리로 세면 목록이 프로모션으로 가득 찬다.
 *
 * @param diffRate (최근 매입가 − 기준가) / 기준가. 부호 있는 값이고 정렬은 절대값 기준이다
 */
public record CostDeviation(
        Long productId,
        String productName,
        BigDecimal basePrice,
        BigDecimal latestPurchasePrice,
        BigDecimal diffRate,
        LocalDate latestPurchasedOn) {
}
