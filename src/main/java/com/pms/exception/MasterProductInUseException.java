package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a master product is deleted while one or more of its channel cells are already on the
 * market ({@code platformProductId != null}, FEATURE_2608_06). Deleting the master would leave the
 * live market listings orphaned, so the caller must stop those channels first → 409 Conflict via the
 * generic {@code BusinessException} handler.
 */
public class MasterProductInUseException extends BusinessException {

    public MasterProductInUseException(long onMarketCount) {
        super(onMarketCount + "개 채널이 마켓에 등록돼 있어 삭제할 수 없습니다. 먼저 판매중지 후 삭제하세요.",
                HttpStatus.CONFLICT);
    }
}
