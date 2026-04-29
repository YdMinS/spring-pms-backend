package com.pms.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * ImageValidator - Validates uploaded image files
 *
 * RED PHASE STUB (not implemented yet)
 * Will validate:
 * - File type and MIME type
 * - File size (max 20MB)
 * - File extension
 * - Magic bytes
 * - Directory traversal attacks
 * - Empty files
 */
@Service
public class ImageValidator {

    public void validate(MultipartFile file) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
