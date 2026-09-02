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

    public CoupangRateLimitedException(Instant until) {
        super("쿠팡 호출 제한 — "
                + LocalTime.ofInstant(until, ZoneId.of("Asia/Seoul")).withNano(0)
                + " (KST) 이후 다시 시도하세요.", HttpStatus.TOO_MANY_REQUESTS);
    }
}
