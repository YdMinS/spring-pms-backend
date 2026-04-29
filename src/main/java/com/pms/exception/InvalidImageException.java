package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * InvalidImageException - Thrown when image validation fails
 *
 * Used for:
 * - Invalid file type/MIME type
 * - File size exceeds limit
 * - Invalid file extension
 * - Magic bytes don't match
 * - Directory traversal attempts
 * - Empty files
 */
public class InvalidImageException extends BusinessException {
    public InvalidImageException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
