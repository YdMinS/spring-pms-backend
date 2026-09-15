package com.pms.service.alert;

import com.pms.dto.response.AlertSummaryResponse;

/**
 * 알림 배지 카운트 조회 (FEATURE_2609_49 / D9).
 *
 * <p>🔴 <b>배지·알림·Dashboard 가 공유하는 단일 카운트 창구다.</b> 화면이 자기 목록 길이를 세는 방식을
 * 쓰지 말 것 — 목록은 타입·기간으로 걸러져 있어 "처리할 일"의 총량이 아니다. 항목이 늘면
 * {@link AlertSummaryResponse} 에 필드를 추가한다(서비스·엔드포인트를 늘리지 않는다).
 *
 * <p>마켓을 치지 않고 로컬 DB 만 센다 — 적재는 {@code OrderSyncScheduler}(15분 티어)가 이미 한다.
 */
public interface AlertSummaryService {

    /** 처리 대기 건수(미완결 클레임 · 미답변 문의). 현재 테넌트 기준({@code @TenantId} 자동 적용). */
    AlertSummaryResponse summary();
}
