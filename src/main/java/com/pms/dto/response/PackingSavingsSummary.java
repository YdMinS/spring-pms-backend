package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 포장 절약 전체 합계 (FEATURE_2609_41 / PLAN 2609_41 S2 · S3 · S10 · S12 · S14).
 *
 * <p>🔴 <b>{@code recycledSaving} 과 {@code consolidatedSaving} 은 겹칠 수 있다</b> — 재활용 상자로
 * 합포장한 박스는 양쪽에 모두 들어간다. 그래서 <b>둘을 더하면 {@code totalSaving} 보다 커진다</b>:
 * 화면은 두 값을 나란히 놓되 합계로 제시하면 안 된다.
 *
 * <p>🔴 {@code missingBasisCount}(계산 근거 없음)와 {@code missingShippingFeeCount}(실린 배송비 모름)는
 * <b>다른 숫자</b>다(S14 · S3). 전자는 박스가 통째로 빠지고, 후자는 <b>택배 절약만</b> 빠진다
 * (상자 절약은 그대로 합계에 들어간다).
 *
 * @param parcelCount             집계에 들어간 박스 수(= 계산 근거가 있는 {@code PACKED} 박스).
 *                                🔴 포장 화면을 거치지 않은 주문은 애초에 <b>행이 없다</b> — 0 이 아니다(S1)
 * @param boxSaving               Σ({@code expected_box_cost − actual_box_cost})
 * @param deliverySaving          Σ(무료배송 박스의 {@code expected_delivery_cost − 실제 택배비 1건분}).
 *                                유료배송은 0, 실린 배송비를 모르거나 요율을 못 찾은 박스는 빠진다(S3 · S15)
 * @param totalSaving             {@code boxSaving + deliverySaving}
 * @param recycledParcelCount     재활용 상자를 쓴 박스 수
 * @param recycledSaving          그 박스들의 절약 합계
 * @param consolidatedParcelCount 🔴 합포장 박스 수 — 판정은 <b>옵션 가짓수가 아니라 수량</b>이다.
 *                                같은 옵션 2개를 한 박스에 담아도 판매가는 상자·택배를 2개로 잡았으므로 절약이 난다
 * @param consolidatedSaving      그 박스들의 절약 합계
 * @param missingBasisCount       계산 근거가 없어 <b>통째로 빠진</b> 박스 수({@code expected_box_cost} 가 NULL, S14)
 * @param negativeParcelCount     절약이 음수인 박스 수 — 🔴 합계에서 빼지 않는다(S12).
 *                                거르면 "상자를 비싸게 쓴 것"이 영원히 안 보인다
 * @param missingShippingFeeCount 실린 배송비를 모르는 박스 수(changeset 066 이전 주문, S3).
 *                                🔴 NULL 은 「무료배송」이 아니라 「모른다」다 — 택배 절약만 빠진다
 */
public record PackingSavingsSummary(int parcelCount,
                                    BigDecimal boxSaving,
                                    BigDecimal deliverySaving,
                                    BigDecimal totalSaving,
                                    int recycledParcelCount,
                                    BigDecimal recycledSaving,
                                    int consolidatedParcelCount,
                                    BigDecimal consolidatedSaving,
                                    int missingBasisCount,
                                    int negativeParcelCount,
                                    int missingShippingFeeCount) {
}
