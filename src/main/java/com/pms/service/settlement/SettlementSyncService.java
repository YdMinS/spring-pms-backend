package com.pms.service.settlement;

import com.pms.dto.response.SettlementSyncResponse;
import com.pms.dto.response.SettlementSyncTargetResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 매출내역 적재의 <b>단일 진입점</b> (FEATURE_2609_30 / PLAN D11).
 *
 * <p><b>필수 규칙</b>: 컨트롤러·스케줄러는 여기만 호출한다. 어댑터({@link SettlementSource})나
 * upserter 를 직접 부르면 최소 간격 가드·앵커 갱신·계정 격리가 호출처마다 두 벌이 된다
 * ({@code OrderSyncFacade} 와 같은 자세).
 */
public interface SettlementSyncService {

    /**
     * Manual refresh — re-reads only the delta window ({@code oklyx.coupang.revenue-delta-days}; 11st channels
     * {@code oclyx.settlement.elevenst-revenue-delta-days}, FEATURE_2610_20 / D11).
     *
     * <p>Priority: {@code accountId} (one channel) &gt; {@code sellerId} (one seller) &gt; all.
     * 🔴 Within the per-account minimum interval (default 10 minutes) the market is not called and the local state
     * is returned as is.
     *
     * <p>After an 11st account loads, its server-fee first month may load M0's previous month once more
     * (FEATURE_2610_20 / D45).
     */
    SettlementSyncResponse sync(Long sellerId, Long accountId);

    /**
     * Period backfill. A range over 31 days is split by the adapter into several calls.
     *
     * <p>⚠️ The minimum-interval guard is not applied, and {@code lastSettlementSyncAt} is <b>not updated</b> — if a
     * past-range backfill meant "read up to now", the delta window would skip that range and the manual refresh would
     * be blocked for 10 minutes.
     *
     * <p>After an 11st account loads, its server-fee first month may load M0's previous month once more
     * (FEATURE_2610_20 / D45).
     *
     * @throws IllegalArgumentException {@code from > to} or the account does not exist (→ 400)
     */
    SettlementSyncResponse syncPeriod(Long accountId, LocalDate from, LocalDate to);

    /** 대상 채널 목록 + 마지막 갱신 시각. 자격증명은 포함하지 않는다. */
    List<SettlementSyncTargetResponse> targets(Long sellerId);
}
