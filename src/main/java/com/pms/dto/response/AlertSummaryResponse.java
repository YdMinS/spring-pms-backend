package com.pms.dto.response;

/**
 * 알림 배지 카운트 (GET /api/alerts/summary, FEATURE_2609_49 / D9).
 *
 * <p>🔴 배지·알림·Dashboard 의 <b>단일 창구</b>다 — 화면마다 자기 목록을 세지 말 것. 항목이 늘면
 * 이 record 에 필드를 추가한다(엔드포인트를 늘리지 않는다).
 *
 * <p>🔴 범위는 <b>미완결/미답변 전부</b>(타입 무관·기간 무관, D14)라 목록 화면의 기본 필터
 * (클레임 = 반품 탭 + 최근 14일)와 다르다 — <b>배지 숫자와 화면 행 수가 일치하지 않는 것이 정상</b>이다.
 *
 * @param openClaims           미완결 클레임 수(DONE·REJECTED·WITHDRAWN·STALE 이 아닌 것)
 * @param unansweredInquiries  미답변 고객문의 수
 */
public record AlertSummaryResponse(long openClaims, long unansweredInquiries) { }
