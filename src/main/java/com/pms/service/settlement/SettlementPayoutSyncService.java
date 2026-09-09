package com.pms.service.settlement;

import com.pms.dto.response.SettlementPayoutSyncResponse;

import java.time.YearMonth;

/**
 * 지급내역(지급 묶음) 적재의 <b>단일 진입점</b> (FEATURE_2609_30 / 02 · PLAN D5-2 · D11).
 *
 * <p><b>필수 규칙</b>: 컨트롤러·스케줄러는 여기만 호출한다. 어댑터({@link SettlementSource})나
 * {@link SettlementPayoutUpserter} 를 직접 부르면 처리 순서 고정(settlementDate ASC)·계정 격리·앵커 갱신이
 * 호출처마다 두 벌이 된다.
 *
 * <p>⚠️ 매출내역 적재({@link SettlementSyncService})와 분리한 이유 = 피드가 다르다. 주기(일 1회 vs 주 1회),
 * 축(인식일 구간 vs 인식월), 응답 모양이 전부 다르며, 이쪽만이 지급 묶음을 만든다.
 */
public interface SettlementPayoutSyncService {

    /**
     * 지급내역 적재 + 라인 귀속 + 대사.
     *
     * @param accountId null 이면 지원 플랫폼의 활성 계정 전부
     * @param month     null 이면 계정별로 자동 결정한다 — 최초 실행이면
     *                  {@code payoutBackfillMonths} 개월치, 아니면 당월 + 직전월(정정 흡수)
     * @throws IllegalArgumentException 계정이 없거나 미래 월을 요청했을 때 (→ 400)
     */
    SettlementPayoutSyncResponse syncPayouts(Long accountId, YearMonth month);
}
