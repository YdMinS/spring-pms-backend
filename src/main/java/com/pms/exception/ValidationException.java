package com.pms.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends BusinessException {

    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
