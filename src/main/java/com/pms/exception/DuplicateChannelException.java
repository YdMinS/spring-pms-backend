package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when channel-add targets a (master, seller, platform) that already has a cell
 * (FEATURE_2608_06 / 3b') → 409 Conflict via the generic {@code BusinessException} handler.
 *
 * <p>⚠️ 신규 등록 전용이다. 쿠팡에 이미 올라가 있는 상품을 편입하는 경로는 이 예외를 던지지 않는다 —
 * 한 계정에 같은 물건의 상품페이지가 여럿인 것은 정상이다(온보딩 2026-09-19, 2609_22/D18 부분 번복).</p>
 */
public class DuplicateChannelException extends BusinessException {

    public DuplicateChannelException() {
        super("이미 등록된 채널", HttpStatus.CONFLICT);
    }
}
