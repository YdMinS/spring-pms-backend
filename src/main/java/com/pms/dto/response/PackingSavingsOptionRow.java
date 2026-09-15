package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 옵션별 포장 절약 1행 (FEATURE_2609_41 / PLAN 2609_41 S5 · S13 · S16).
 *
 * <p>🔴 <b>행의 키는 마스터 옵션이다.</b> 계산·배분은 채널 옵션({@code ProductListingOption}) 단위로 한다
 * — 상자비·택배비가 채널 셀 × 옵션으로 해석되기 때문이다 — 하지만 응답을 만들 때 마스터 옵션으로 합친다.
 * 채널 옵션으로 내보내면 같은 옵션이 채널 수만큼 행으로 갈라져 <b>사실상 채널 축이 생긴다</b>(S13 위반).
 *
 * <p>🔴 {@code masterProductId}/{@code masterProductName} 를 같이 싣는 이유: 화면이 마스터 행에 접어
 * 보여주고 펼치면 옵션별로 내려야 하는데(S16), 이 값이 없으면 프론트가 마스터를 알아내려 조회를 한 번 더 한다.
 *
 * <p>🔴 옵션별 합계는 {@link PackingSavingsSummary} 의 전체와 <b>정확히 같다</b> — 배분의 반올림 잔돈까지
 * 가장 큰 몫에 몰아주기 때문이다(S5). 두 숫자가 어긋나는 순간 화면 전체가 신뢰를 잃는다.
 *
 * @param parcelCount 이 옵션이 담긴 박스 수 — 🔴 박스마다 여러 옵션이 들어가므로 <b>행끼리 더하면</b>
 *                    전체 박스 수보다 커진다
 */
public record PackingSavingsOptionRow(Long masterProductId,
                                      String masterProductName,
                                      Long masterOptionId,
                                      String masterOptionName,
                                      int parcelCount,
                                      BigDecimal boxSaving,
                                      BigDecimal deliverySaving,
                                      BigDecimal totalSaving) {
}
