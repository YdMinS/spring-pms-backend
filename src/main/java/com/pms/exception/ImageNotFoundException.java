package com.pms.exception;

import org.springframework.http.HttpStatus;

/**
 * ImageNotFoundException - Thrown when image file not found
 *
 * Used for:
 * - Image file doesn't exist on disk
 * - Product has no associated image (imageUrl is null)
 * - 404 Not Found response
 */
public class ImageNotFoundException extends BusinessException {
    public ImageNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
