package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-platform settlement revenue lookup seam (FEATURE_2609_30 / PLAN D5).
 *
 * <p><b>Rule</b>: the neutral load service ({@link SettlementSyncService}) depends on this interface only — do not let
 * a platform's paths, field names or paging rules leak into the neutral side (2609_26 principle). Implementations
 * are {@code service/settlement/coupang/CoupangSettlementSource} and
 * {@code service/settlement/elevenst/ElevenstSettlementSource} (FEATURE_2610_20), injected as
 * {@code Map<Platform, ...>}.
 *
 * <p>⚠️ Paging and window splitting (Coupang · 11st 31-day cap) are handled <b>inside the implementation</b>. Callers
 * pass the range they want and never show a 400 to the user as is.
 */
public interface SettlementSource {

    /** {@code marketplace_account.platform} 과 대조할 값. */
    Platform platform();

    /**
     * 이 계정의 [from, to] 매출인식일 구간 라인을 전부 읽어온다(페이징 내부 처리).
     *
     * <p>⚠️ 라인이 많으면 전부 메모리에 올라간다 — 적재 경로는 페이지 단위로 커밋해야 하므로
     * {@link #fetchRevenue(MarketplaceAccount, LocalDate, LocalDate, Consumer)} 를 쓴다.
     * 이 메서드는 테스트·단발 조회용 편의 형태다.
     */
    default List<SettlementLineDraft> fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to) {
        List<SettlementLineDraft> all = new ArrayList<>();
        fetchRevenue(account, from, to, all::addAll);
        return all;
    }

    /**
     * 위와 같되 <b>페이지 1개를 읽을 때마다</b> 콜백한다 — 적재 서비스가 페이지 단위로 커밋하기 위한 형태다.
     *
     * <p>🔴 3페이지에서 실패해도 1~2페이지는 남아야 재실행이 싸다. 전량을 모아 한 번에 저장하면
     * 마지막 페이지의 실패가 앞의 성공을 전부 되돌린다.
     */
    void fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to,
                      Consumer<List<SettlementLineDraft>> pageConsumer);

    /**
     * Every payout of this account for {@code month} (revenue-recognition month) (FEATURE_2609_30 / 02 · PLAN D5-2).
     *
     * <p>🔴 A <b>different feed</b> from the revenue history: one axis (the recognition month), no paging, and one
     * element = one payout. Several arrive in the same month, so an implementation <b>never merges</b> elements
     * (D5-3).
     *
     * <p>⚠️ A month after the current one gets a 400 from the platform — callers never pass a future month.
     *
     * <p>A platform with no payout API returns nothing here and registers a {@link LocalPayoutBuilder} instead —
     * 11st (FEATURE_2610_20 / D30); the payout load then never calls this method for that platform.
     */
    List<SettlementPayoutDraft> fetchPayouts(MarketplaceAccount account, YearMonth month);
}
