package com.pms.dto.response;

/**
 * 알림 배지 카운트 (GET /api/alerts/summary, FEATURE_2609_49 / D9 · FEATURE_2609_51 / D3·D11).
 *
 * <p>🔴 배지·알림·Dashboard 의 <b>단일 창구</b>다 — 화면마다 자기 목록을 세지 말 것. 항목이 늘면
 * 이 record 에 필드를 추가한다(엔드포인트를 늘리지 않는다).
 *
 * <p>🔴 {@code openClaims}·{@code unansweredInquiries} 의 범위는 <b>미완결/미답변 전부</b>(타입 무관·기간
 * 무관, 2609_49 D14)라 목록 화면의 기본 필터(클레임 = 반품 탭 + 최근 14일)와 다르다 — <b>배지 숫자와
 * 화면 행 수가 일치하지 않는 것이 정상</b>이다. 🔴 이 두 값은 <b>바뀌면 안 된다</b>(사이드바 배지).
 *
 * <p>🔴 세 신규 카운트는 모두 <b>전량취소분을 제외</b>한다(2609_51 D6) — 이미 취소된 주문을
 * "발주처리하라"고 재촉하지 않기 위해서다.
 *
 * @param openClaims           미완결 클레임 수(DONE·REJECTED·WITHDRAWN·STALE 이 아닌 것). 기간 무관
 * @param unansweredInquiries  미답변 고객문의 수. 기간 무관
 * @param paidOrders 결제완료 <b>주문</b> 수 = {@code 출고관리} 메뉴 배지(2609_51 D7, 2026-09-16 단위 변경).
 *                   🔴 {@code newOrders} 와 <b>같은 주문 단위</b>다 — 상품 수로 세면 상품 2개짜리 주문 하나가
 *                   메뉴 2 · 종 1 로 보인다. 다만 <b>기간 상한은 없으므로</b>(D8) 14일이 지난 결제완료 주문이
 *                   있으면 {@code newOrders} 보다 큰 것은 여전히 정상이다
 * @param newOrders  새 주문 알림 건수 = <b>주문 단위</b>, 최근 {@code coupang.sync-days}(D5·D8)
 * @param todoCount  종 아이콘 배지 = {@code newOrders + 기간이 걸린 미완결 클레임 + 기간이 걸린 미답변 문의}.
 *                   🔴 알림 목록의 <b>행 수와 정확히 같다</b>(D3) — 클라이언트가 따로 더하지 않게 서버가 준다.
 *                   🔴 <b>{@code openClaims + unansweredInquiries} 를 더한 값이 아니다</b>: 이쪽에는
 *                   목록과 같은 기간(D8)이 걸려 있다. 자동 종결이 정상이면 두 값이 같고, 고장 나면
 *                   달라진다 — 그 차이 자체가 고장 신호다
 */
public record AlertSummaryResponse(long openClaims, long unansweredInquiries,
                                   long paidOrders, long newOrders, long todoCount) {
}
