package com.pms.service.coupang;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트 전용 이동 가능한 시계.
 *
 * <p>쿨다운 경과·토큰 충전을 표현하려면 시각이 움직여야 한다 — {@link Clock#fixed} 는 고정이라 쓸 수 없다.
 * 쿠팡 시간 의존 컴포넌트(쿨다운 가드·호출 예산)가 공유한다. 복사해서 쓰지 말 것.
 */
final class MutableClock extends Clock {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
