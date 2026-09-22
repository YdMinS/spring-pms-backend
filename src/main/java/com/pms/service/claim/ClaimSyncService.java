package com.pms.service.claim;

import com.pms.dto.response.ClaimSyncResponse;

/**
 * 채널 1개의 반품·교환만 다시 가져오기 (FEATURE_2609_70 / D14·D15).
 *
 * 클레임 적재는 주문 동기화 안에도 붙어 있지만(2609_18 D15), 반품만 보려고 주문 전체를 돌릴 이유가
 * 없어서 같은 적재를 단독으로 부르는 입구를 따로 둔다 — 고객문의가 먼저 같은 길을 갔다
 * ({@link com.pms.service.inquiry.InquirySyncService}). <b>적재 규칙은 한 벌</b>이다: 두 입구 모두
 * {@code syncCancels} · {@code trackOpenClaims} · {@code syncExchanges} · 백필을 그대로 부르며,
 * 여기서 적재를 다시 구현하지 않는다.
 *
 * <p>⚠️ 계정 단위다. 여러 채널을 도는 것은 화면이 한다 — 그래야 어느 채널을 조회 중인지·어느 채널이
 * 실패했는지 진행 상황을 그릴 수 있다(주문·문의 동기화 화면과 같은 방식).
 */
public interface ClaimSyncService {

    /**
     * 채널 1개의 클레임을 가져온다. 주문 조회(ordersheets)·문의 적재는 돌지 않는다.
     *
     * @param accountId 판매 계정(채널) id
     * @return 건너뛴 회차인지 + 회차 시각 (건수는 담지 않는다 — D16)
     * @throws com.pms.exception.ResourceNotFoundException 계정이 없으면
     * @throws IllegalArgumentException 클레임 조회를 지원하지 않는 플랫폼이면(→ 400)
     */
    ClaimSyncResponse sync(Long accountId);
}
