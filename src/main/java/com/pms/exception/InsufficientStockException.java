package com.pms.exception;

import org.springframework.http.HttpStatus;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    public InsufficientStockException() {
        super("Insufficient stock", HttpStatus.CONFLICT);
    }
}
