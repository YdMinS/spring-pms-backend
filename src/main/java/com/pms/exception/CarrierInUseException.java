package com.pms.exception;

/**
 * Carrier 마스터가 요율(carrier_rate) 등에서 FK 로 참조되어 삭제할 수 없을 때 발생.
 * GlobalExceptionHandler 에서 409 Conflict 로 매핑.
 */
public class CarrierInUseException extends RuntimeException {

    public CarrierInUseException(Long id) {
        super("Carrier is referenced by existing rates and cannot be deleted: " + id);
    }
}
