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
     * Storage backend selector: "local" (disk, default) or "s3".
     * local/test profiles keep "local"; dev/prod set "s3" (see application-{dev,prod}.yml).
     */
    private String type = "local";

    /**
     * S3-specific settings (only used when type=s3).
     */
    private S3 s3 = new S3();

    @Getter
    @Setter
    public static class S3 {
        /** Bucket name, e.g. oclyx-product-images-dev. */
        private String bucket;

        /** AWS region, e.g. ap-northeast-2 (Seoul). */
        private String region = "ap-northeast-2";

        /** Key prefix; final key = {keyPrefix}/{tenantId}/products/{filename}. */
        private String keyPrefix = "tenants";

        /**
         * Public base URL. If null, computed as https://{bucket}.s3.{region}.amazonaws.com.
         * Override for CDN / custom domain.
         */
        private String publicBaseUrl;
    }

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
