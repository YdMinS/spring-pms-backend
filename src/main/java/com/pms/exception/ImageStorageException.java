package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * ImageStorageException - Thrown when image storage/retrieval fails
 *
 * Used for:
 * - File I/O errors
 * - Directory creation failures
 * - File read/write failures
 */
public class ImageStorageException extends BusinessException {
    public ImageStorageException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public ImageStorageException(String message, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
        initCause(cause);
    }
}
