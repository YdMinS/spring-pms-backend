package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * ImageStorageProperties - Configuration for image storage
 *
 * Bindable from application.yml via image.storage prefix
 * Provides settings for file upload, validation, and storage
 */
@Component
@ConfigurationProperties(prefix = "image.storage")
@Getter
@Setter
public class ImageStorageProperties {

    /**
     * Base directory for storing uploaded images
     * Default: uploads/products
     */
    private String uploadDir = "uploads/products";

    /**
     * Base URL for image retrieval
     * Default: /api/products
     */
    private String baseUrl = "/api/products";

    /**
     * Maximum file size in bytes (20MB = 20971520 bytes)
     * Default: 20971520
     */
    private long maxFileSize = 20971520;

    /**
     * Allowed MIME types for image uploads
     * Default: image/jpeg, image/png
     */
    private String allowedMimeTypes = "image/jpeg,image/png";

    /**
     * Allowed file extensions for image uploads
     * Default: jpg, jpeg, png
     */
    private String allowedExtensions = "jpg,jpeg,png";

    /**
     * Get allowed MIME types as list
     */
    public List<String> getAllowedMimeTypesList() {
        return Arrays.asList(allowedMimeTypes.split(","));
    }

    /**
     * Get allowed extensions as list (lowercase)
     */
    public List<String> getAllowedExtensionsList() {
        return Arrays.asList(allowedExtensions.toLowerCase().split(","));
    }
}
