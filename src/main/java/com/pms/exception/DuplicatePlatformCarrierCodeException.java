package com.pms.exception;

/**
 * 같은 (carrier, platform) 조합의 플랫폼 코드가 이미 존재할 때 발생.
 * GlobalExceptionHandler 에서 409 Conflict 로 매핑.
 */
public class DuplicatePlatformCarrierCodeException extends RuntimeException {

    public DuplicatePlatformCarrierCodeException(Long carrierId, String platform) {
        super("Platform code already exists for carrier " + carrierId + " and platform " + platform);
    }
}
