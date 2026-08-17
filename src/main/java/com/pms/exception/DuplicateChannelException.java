package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when channel-add targets a (master, seller, platform) that already has a cell
 * (FEATURE_2608_06 / 3b'). One market product page per account → 409 Conflict via the generic
 * {@code BusinessException} handler.
 */
public class DuplicateChannelException extends BusinessException {

    public DuplicateChannelException() {
        super("이미 등록된 채널", HttpStatus.CONFLICT);
    }
}
