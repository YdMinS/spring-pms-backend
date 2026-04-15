package com.pms.exception;

import com.pms.dto.common.ResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ResponseDTO<Void>> handleDuplicateEmail(DuplicateEmailException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ResponseDTO.failure(ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseDTO<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ResponseDTO.failure(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDTO<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((e1, e2) -> e1 + ", " + e2)
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.failure(message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDTO<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ResponseDTO.failure("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ResponseDTO<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ResponseDTO.failure("Authentication failed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDTO<Void>> handleException(Exception ex) {
        log.error("Unexpected exception occurred", ex);
        // Never expose stack trace in response body
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.failure("Internal server error"));
    }
}
