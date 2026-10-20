package com.pms.service.coupang;

/**
 * The <b>intent</b> of one sync (FEATURE_2609_49 / D6·D7).
 *
 * <p>Its role differs from {@link OrderSyncScope} — the scope is "which statuses are read" (the service layer's status
 * set), the preset is "who calls and why" (the boundary's contract). The preset picks the scope and the
 * <b>window</b> together.
 *
 * <p>The steps (cancel correction · return tracking · exchanges · backfill · inquiries) run <b>all and the same for
 * both presets</b>. The steps the 15-minute tier needs are the steps the manual button needs, and the only difference
 * from the night round is the order lookup — do not make a per-step on/off axis (only combinations grow, nobody uses
 * them).
 *
 * <p>🔴 <b>Do not add methods to this enum.</b> A mapping such as {@code scope()} would make the RECONCILE value dead
 * code — that path uses the {@code syncAccount(account, SyncWindow)} overload, which sets FULL inside on its own
 * ({@code CoupangOrderSyncServiceImpl:68}). The branch stays in one place: the switch of the Coupang order round
 * ({@code CoupangOrderSyncAdapter}, FEATURE_2610_23 / D21).
 */
public enum OrderSyncPreset {

    /**
     * 좁은 조회 = 수동 버튼(D6)과 15분 스케줄(D2)의 값. 활성 상태 2종만 본다.
     *
     * <p>활성 상태의 창은 {@code recent(sync-days)} 로 <b>앵커와 무관하게 14일 고정</b>이므로,
     * 이 프리셋이 자주 돌아도 조회 구간이 좁아지지 않는다.
     */
    QUICK,

    /**
     * 전량 리컨실 = 새벽 3시 스케줄(D1). 전 상태 + <b>창을 앵커가 아니라 {@code sync-days} 로 고정</b>한다.
     *
     * <p>🔴 창을 고정하는 이유: 종결 상태의 기본 창은 {@code recentSince(lastOrderSyncAt, 3, 14)} 인데,
     * QUICK 이 15분마다 {@code lastOrderSyncAt} 을 찍으면 경과가 항상 0 → 창이 하한 3일로 붕괴한다.
     * 그러면 "하루 안에 수렴하는 안전망"이 3일 창짜리 좁은 조회가 되어 이 프리셋의 존재 이유가 사라진다.
     */
    RECONCILE
}
