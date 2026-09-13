package com.pms.exception;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Thrown while the Coupang 429 cooldown window is open.
 *
 * Coupang's guidance after a 429 is to stop calling for ~10 minutes; retrying through the ban
 * risks a permanent IP block. This exception short-circuits the call so a user re-clicking "sync"
 * cannot extend it, and tells them when it is safe to try again.
 */
public class CoupangRateLimitedException extends BusinessException {

    private final Instant retryAfter;

    public CoupangRateLimitedException(Instant until) {
        super("쿠팡 호출 제한 — "
                + LocalTime.ofInstant(until, ZoneId.of("Asia/Seoul")).withNano(0)
                + " (KST) 이후 다시 시도하세요.", HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfter = until;
    }

    /**
     * 재시도 가능 시각(쿨다운 종료).
     *
     * <p>메시지에 이미 들어 있지만, <b>200 으로 내려가는 결과</b>에 그 시각을 실어야 하는 호출부가 있다
     * (FEATURE_2609_39 / PLAN D26 — 마켓 반영은 중간에 끊겨도 이미 전송된 건을 돌려줘야 하므로 429 로
     * 세우지 않는다). 문장을 다시 파싱하지 않게 값 그대로 들고 있는다.</p>
     */
    public Instant getRetryAfter() {
        return retryAfter;
    }
}
