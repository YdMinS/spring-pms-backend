package com.pms.dto.response;

import java.util.List;

/**
 * 원가 파급 확정 실행 결과 (FEATURE_2609_28 / PLAN D4 ②).
 *
 * <p>셀마다 독립 트랜잭션({@code REQUIRES_NEW})이라 하나가 실패해도 나머지는 커밋된다 — 그래서 전체
 * 성공/실패가 아니라 <b>PARTIAL</b> 이 존재한다(2609_02 패턴).
 *
 * <p>🔴 채널 push 는 하지 않는다. 셀에 {@code needsMarketSync = true} 만 남고 실제 전송은 기존
 * 상품수정 경로에서 사람이 실행한다(PLAN D4 ③).
 */
public record PropagationApplyResult(
        String status,          // SUCCESS | PARTIAL | FAILED
        int requestedMasters,
        int propagatedCells,
        int skippedCells,
        int failedCells,
        List<MasterResult> results) {

    /** 마스터 1건의 결과. {@code error} 는 마스터 자체가 실패했을 때만 채워진다(셀 실패는 집계로 센다). */
    public record MasterResult(Long masterId, int propagated, int skipped, int failed, String error) {
    }
}
