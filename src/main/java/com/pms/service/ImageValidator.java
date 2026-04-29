package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.exception.InvalidImageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * ImageValidator - Validates uploaded image files before storage
 *
 * Performs 6 validation checks:
 * 1. File not empty
 * 2. File size within limit (20MB)
 * 3. MIME type allowed
 * 4. File extension allowed
 * 5. Magic bytes match MIME type
 * 6. No directory traversal attempts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageValidator {

    private final ImageStorageProperties properties;

    /**
     * Validate MultipartFile before storage
     *
     * @param file File to validate
     * @throws InvalidImageException if validation fails
     */
    public void validate(MultipartFile file) {
        // 1. Empty file check
        if (file.isEmpty() || file.getSize() == 0) {
            throw new InvalidImageException("File is empty");
        }

        // 2. File size check
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new InvalidImageException("File size exceeds 20MB limit");
        }

        // 3. MIME type validation
        String contentType = file.getContentType();
        if (!properties.getAllowedMimeTypesList().contains(contentType)) {
            throw new InvalidImageException("Invalid MIME type: " + contentType);
        }

        // 4. File extension validation
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        if (!properties.getAllowedExtensionsList().contains(extension.toLowerCase())) {
            throw new InvalidImageException("Invalid file extension: " + extension);
        }

        // 5. Magic bytes validation
        validateMagicBytes(file, contentType);

        // 6. Directory traversal check
        if (originalFilename.contains("..") || originalFilename.contains("/") ||
                originalFilename.contains("\\")) {
            throw new InvalidImageException("Invalid file path: directory traversal detected");
        }

        log.debug("Image validation passed for file: {}", originalFilename);
    }

    /**
     * Validate magic bytes match MIME type
     */
    private void validateMagicBytes(MultipartFile file, String contentType) {
        byte[] magic = new byte[8];
        try (InputStream is = file.getInputStream()) {
            is.read(magic, 0, magic.length);
        } catch (IOException e) {
            throw new InvalidImageException("Failed to read file for magic bytes validation");
        }

        if ("image/jpeg".equals(contentType)) {
            // JPEG magic bytes: FF D8 FF
            if (!(magic[0] == (byte) 0xFF && magic[1] == (byte) 0xD8 && magic[2] == (byte) 0xFF)) {
                throw new InvalidImageException("Invalid magic bytes for JPEG");
            }
        } else if ("image/png".equals(contentType)) {
            // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
            if (!(magic[0] == (byte) 0x89 && magic[1] == 0x50 &&
                    magic[2] == 0x4E && magic[3] == 0x47)) {
                throw new InvalidImageException("Invalid magic bytes for PNG");
            }
        }
    }

    /**
     * Extract file extension from filename
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
