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
     * 수동 갱신 — delta 창({@code revenue-delta-days})만 다시 읽는다.
     *
     * <p>우선순위: {@code accountId}(단건) &gt; {@code sellerId}(판매자 단위) &gt; 전체.
     * 🔴 계정별 최소 간격(기본 10분) 안이면 마켓을 호출하지 않고 로컬 상태를 그대로 돌려준다.
     */
    SettlementSyncResponse sync(Long sellerId, Long accountId);

    /**
     * 기간 지정 백필. 31일을 넘는 구간은 어댑터가 잘라 여러 번 호출한다.
     *
     * <p>⚠️ 최소 간격 가드를 적용하지 않고, {@code lastSettlementSyncAt} 도 <b>갱신하지 않는다</b> —
     * 과거 구간 백필이 "최신까지 읽었다"는 뜻이 되면 delta 창이 그 구간을 건너뛰고, 수동 갱신까지 10분
     * 막힌다.
     *
     * @throws IllegalArgumentException {@code from > to} 이거나 계정이 없을 때 (→ 400)
     */
    SettlementSyncResponse syncPeriod(Long accountId, LocalDate from, LocalDate to);

    /** 대상 채널 목록 + 마지막 갱신 시각. 자격증명은 포함하지 않는다. */
    List<SettlementSyncTargetResponse> targets(Long sellerId);
}
