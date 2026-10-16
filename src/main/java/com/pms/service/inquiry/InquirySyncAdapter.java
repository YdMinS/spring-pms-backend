package com.pms.service.inquiry;

import com.pms.domain.Platform;
import com.pms.domain.MarketplaceAccount;

/**
 * Per-platform customer inquiry sync entry (FEATURE_2609_23 / PLAN D19 — the seam only). Implementations: Coupang ·
 * 11st (FEATURE_2610_17 / D16).
 *
 * Abstracting the account model is out of scope ({@code project_multimarket_extensibility_debt}'s three [HIGH] items
 * are paid right before Naver). Same shape as {@code ClaimSyncAdapter} — only {@code platform()}; callers pick the
 * adapter whose platform matches the account, so {@code supports(account)} · {@code priority()} are not needed.
 */
public interface InquirySyncAdapter {

    /** {@code marketplace_account.platform} 과 대조할 값. 예: {@link Platform#COUPANG}. */
    Platform platform();

    /**
     * 계정 1건의 문의 적재(유형 전부) + STALE 종결.
     *
     * ⚠️ 한 유형이라도 실패하면 <b>예외를 던진다</b>. 실패를 {@link InquirySyncResult} 필드로 돌려주면
     * 호출자가 성공으로 보고 {@code lastInquirySyncAt} 을 갱신해 놓친 구간이 영구히 사라진다.
     */
    InquirySyncResult syncInquiries(MarketplaceAccount account);

    /**
     * The order-sync round's entry: {@code nightly} = the 3 a.m. RECONCILE round. Platforms with one window for every
     * round ignore it (default); 11st reads its closed urgent notices only at night (FEATURE_2610_17 / D51).
     */
    default InquirySyncResult syncInquiries(MarketplaceAccount account, boolean nightly) {
        return syncInquiries(account);
    }

    /**
     * 회차 결과 — 로그·검증용.
     *
     * ⚠️ {@code OrderSyncResult} 에 합치지 말 것(응답 계약이 흔들려 클라이언트가 함께 움직인다).
     *
     * @param slices      조회한 슬라이스 수(유형 합산)
     * @param pages       실제로 읽은 페이지 수
     * @param upserted    적재를 시도한 문의 건수
     * @param staleClosed 이번 회차에 STALE 로 강제 종결한 건수
     */
    record InquirySyncResult(int slices, int pages, int upserted, int staleClosed) {
    }
}
