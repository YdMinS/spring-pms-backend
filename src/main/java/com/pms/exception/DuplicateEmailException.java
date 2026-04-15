package com.pms.exception;

import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends BusinessException {

    public DuplicateEmailException(String email) {
        super(String.format("Email already exists: %s", email), HttpStatus.CONFLICT);
    }
}
