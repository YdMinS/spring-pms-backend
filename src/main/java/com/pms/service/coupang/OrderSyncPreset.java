package com.pms.service.coupang;

/**
 * 동기화 한 번의 <b>의도</b> (FEATURE_2609_49 / D6·D7).
 *
 * <p>{@link OrderSyncScope} 와 역할이 다르다 — scope 는 "어떤 상태를 조회하는가"(서비스 계층의 상태
 * 집합)이고, preset 은 "누가 왜 부르는가"(경계의 계약)다. preset 이 scope 와 <b>창</b>을 함께 고른다.
 *
 * <p>단계(취소 보정·반품 추적·교환·백필·문의)는 <b>두 프리셋이 동일하게 전부</b> 돈다. 15분 티어가
 * 필요한 단계와 수동 버튼이 필요한 단계가 같고, 야간과의 차이는 주문 조회뿐이기 때문이다 — 단계별
 * on/off 축을 만들지 말 것(조합만 늘고 쓰는 곳이 없다).
 *
 * <p>🔴 <b>이 enum 에 메서드를 만들지 말 것.</b> {@code scope()} 같은 매핑을 넣으면 RECONCILE 쪽 값이
 * 죽은 코드가 된다 — 그 경로는 {@code syncAccount(account, SyncWindow)} 오버로드를 쓰고, 그 오버로드가
 * 내부에서 FULL 을 스스로 박기 때문이다({@code CoupangOrderSyncServiceImpl:68}). 분기는 파사드의
 * switch 한 곳에만 둔다.
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
