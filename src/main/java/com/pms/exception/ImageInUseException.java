package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a product image is deleted while a master pool references it and that reference is placed on
 * a zone or the cover photo (FEATURE_2608_06 / 40). Deleting the source slot would break the live-link seen
 * by other masters / on-market listings, so the caller must unmap the placement first → 409 Conflict via the
 * generic {@code BusinessException} handler. DRAFT placements are included.
 */
public class ImageInUseException extends BusinessException {

    public ImageInUseException() {
        super("다른 상품 리스팅에서 사용 중입니다. 배치를 먼저 해제하세요.", HttpStatus.CONFLICT);
    }
}
