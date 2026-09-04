package com.pms.service.coupang;

import java.util.Arrays;
import java.util.List;

/**
 * 동기화 한 번이 조회할 <b>상태 범위</b> — 호출부(화면)가 고른다(PLAN 2609_16 D1).
 *
 * 쿠팡 ordersheets 의 {@code status} 는 필수·단일값이라 <b>상태 수 = 쿠팡 왕복 수</b>다. 전 상태를 도는
 * {@link #FULL} 은 6배치, 활성 상태만 도는 {@link #ACTIVE} 는 2배치다 — 종결 상태가 필요 없는 화면
 * (출고관리)이 왕복을 6 → 2 로 줄이는 유일한 수단이다.
 *
 * ⚠️ {@code ACTIVE} 로 돈 계정은 이번 실행에서 <b>종결 전이를 받지 못한다</b>. 우리가 발송처리한 건은
 * 발송 성공 시 write-back 으로 이미 {@code DEPARTURE} 이고(2609_07 D4), WING 에서 직접 발송한 건은
 * 다음 {@code FULL} 동기화(주문내역·구매목록)가 따라잡는다.
 */
public enum OrderSyncScope {

    /** 전 상태(결제완료~추적불가). 기본값 — 파라미터를 안 주면 이 동작이다(D3). */
    FULL,

    /** 활성 상태만(결제완료·상품준비중). 종결 상태는 조회하지 않는다. */
    ACTIVE;

    /**
     * 이 범위가 조회할 상태 — 순서는 enum 선언(라이프사이클) 순을 유지한다.
     *
     * ⚠️ {@code ACTIVE} 는 {@link CoupangOrderStatus#isTerminal()} 의 <b>여집합</b>으로만 정의한다(D2).
     * 상태 목록을 여기에 복제하면 상태가 늘 때 두 곳이 갈라진다.
     */
    public List<CoupangOrderStatus> statuses() {
        return switch (this) {
            case FULL -> List.of(CoupangOrderStatus.values());
            case ACTIVE -> Arrays.stream(CoupangOrderStatus.values())
                    .filter(s -> !s.isTerminal())
                    .toList();
        };
    }
}
