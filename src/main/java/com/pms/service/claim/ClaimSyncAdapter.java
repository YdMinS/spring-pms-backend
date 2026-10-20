package com.pms.service.claim;

import com.pms.domain.Platform;
import com.pms.domain.MarketplaceAccount;

/**
 * Per-platform claim sync entry (FEATURE_2609_18 / PLAN D21 — the seam only).
 *
 * Coupang: {@link #syncExchanges} loads exchanges only — Coupang returns ride on the cancel-correction response (D15),
 * which the order round and {@link #syncClaimsOnly} call; moving them into {@link #syncExchanges} would add Coupang
 * calls. 11st: returns and exchanges both (FEATURE_2610_17 / D15 — the 11st round has no cancel-correction step to
 * carry the returns).
 *
 * <p>⚠️ Only {@code platform()} — callers pick the one adapter whose platform matches the account;
 * {@code supports(account)} · {@code priority()} are not needed for that.
 */
public interface ClaimSyncAdapter {

    /** {@code marketplace_account.platform} 과 대조할 값. 예: {@link Platform#COUPANG}. */
    Platform platform();

    /** New-window load (+ open-claim tracking for Coupang). Result of one account. */
    ClaimSyncResult syncExchanges(MarketplaceAccount account);

    /**
     * The claim screen's claims-only round of one account (FEATURE_2609_70 / D14 · D15-1, moved here by
     * FEATURE_2610_23 / D12 · D21). Runs under the caller's lock and the account's tenant. A load or tracking failure
     * propagates, and only a round that finished its tracking stamps {@code last_claim_sync_at} — never the order
     * sync status.
     */
    void syncClaimsOnly(MarketplaceAccount account);

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
