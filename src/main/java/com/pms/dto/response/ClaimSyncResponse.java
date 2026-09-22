package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * 반품·교환만 다시 가져오기 응답 (POST /api/claims/sync, FEATURE_2609_70 / D16).
 *
 * ⚠️ {@link OrderSyncResponse}·{@link InquirySyncResponse} 에 합치지 말 것 — 클라이언트 두 벌(웹·모바일)이
 * 쓰는 계약이라 필드가 늘면 함께 움직여야 한다.
 *
 * <p>🔴 <b>건수를 담지 않는다.</b> 클레임 적재 경로가 세는 것은 조회한 페이지·슬라이스 수뿐이라
 * 「신규 N건」을 만들 수 없다 — 없는 숫자를 지어내면 화면이 그걸 진짜로 보여준다.
 *
 * @param accountId 어느 채널(판매 계정)을 가져왔는지. 화면이 채널별 진행을 그리는 데 쓴다
 * @param skipped   같은 채널이 이미 동기화 중이라 쿠팡을 치지 않고 건너뛴 회차인지(D15)
 * @param syncedAt  이 회차의 시각. 건너뛴 회차도 채워진다(화면이 "언제 눌렀는지"를 쓴다)
 */
public record ClaimSyncResponse(Long accountId, boolean skipped, LocalDateTime syncedAt) {
}
