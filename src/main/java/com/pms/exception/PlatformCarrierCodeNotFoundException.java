package com.pms.exception;

/**
 * 플랫폼 택배사 코드를 id 로 찾지 못했을 때 발생. GlobalExceptionHandler 에서 404 로 매핑.
 */
public class PlatformCarrierCodeNotFoundException extends RuntimeException {

    public PlatformCarrierCodeNotFoundException(Long id) {
        super("Platform carrier code not found with id: " + id);
    }
}
