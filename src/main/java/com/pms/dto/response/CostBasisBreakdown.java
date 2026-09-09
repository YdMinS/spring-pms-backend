package com.pms.dto.response;

import com.pms.domain.CostBasis;

import java.math.BigDecimal;

/**
 * 기간별 원가 근거 구성비 한 줄 (FEATURE_2609_28 / PLAN D20).
 *
 * <p>이 숫자가 <b>FIFO 투자 여부를 결정한다</b> — 추정 등급({@link CostBasis#LISTED})의 비중이 낮으면
 * FIFO 를 영원히 안 만들어도 된다. 그래서 등급 이름이 정확도를 과장하면 안 된다(CostBasis javadoc).
 *
 * <p>⚠️ {@code lineCount} 가 {@code long} 인 것은 JPQL {@code count(l)} 이 {@code Long} 이기 때문이다.
 * 생성자 projection 은 시그니처가 정확히 맞아야 한다 — {@code int} 로 두면 런타임에 터진다.
 *
 * <p>⚠️ 노출 엔드포인트는 여기 없다(정산·손익 화면의 영역). 지금은 repository 메서드까지다.
 */
public record CostBasisBreakdown(CostBasis basis, long lineCount, BigDecimal costSum) {
}
