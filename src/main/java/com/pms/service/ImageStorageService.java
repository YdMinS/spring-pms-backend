package com.pms.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * ImageStorageService - Manages image file storage and retrieval
 *
 * RED PHASE STUB (not implemented yet)
 * Will handle:
 * - File upload and storage
 * - Directory creation
 * - Unique filename generation
 * - File retrieval
 * - File deletion
 * - Storage path: /uploads/products/
 */
@Service
public class ImageStorageService {

    public String uploadImage(MultipartFile file) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public byte[] getImage(String filename) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void deleteImage(String filename) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
