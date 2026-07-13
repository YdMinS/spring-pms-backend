package com.pms.exception;

/**
 * Carrier 마스터를 id 로 찾지 못했을 때 발생. GlobalExceptionHandler 에서 404 로 매핑.
 */
public class CarrierNotFoundException extends RuntimeException {

    public CarrierNotFoundException(Long id) {
        super("Carrier not found with id: " + id);
    }
}
