package com.pms.service.claim;

import com.pms.domain.MarketplaceAccount;

/**
 * 플랫폼별 교환 클레임 동기화 진입점 (FEATURE_2609_18 / PLAN D21 — seam 만 확보한다).
 *
 * 반품은 취소 보정 응답에 얹혀 적재되므로(D15) 이 seam 밖에 남는다 — 지금 옮기면 쿠팡 호출이 는다.
 *
 * <p>⚠️ {@code platform()} 하나만 둔다 — {@code supports(account)}·{@code priority()} 는 구현이
 * 1개인 지금 쓰이지 않는다.
 */
public interface ClaimSyncAdapter {

    /** {@code marketplace_account.platform} 과 대조할 값. 예: "COUPANG". */
    String platform();

    /** 신규 창 적재 + 미완결 추적. 계정 1건 처리 결과. */
    ClaimSyncResult syncExchanges(MarketplaceAccount account);

    /**
     * 회차 결과 — 로그·검증용.
     *
     * ⚠️ {@code OrderSyncResult} 에 합치지 말 것(응답 계약이 흔들려 클라이언트 3벌이 함께 움직인다).
     *
     * @param pages       신규 창에서 읽은 페이지 수
     * @param slices      추적으로 조회한 슬라이스 수
     * @param staleClosed 이번 회차에 STALE 로 강제 종결한 건수
     */
    record ClaimSyncResult(int pages, int slices, int staleClosed) {
    }
}
