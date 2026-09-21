package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
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

        /**
         * 우리 저장소가 내주는 공개 주소의 앞부분 — 설정된 override, 없으면 bucket+region 으로 계산.
         *
         * <p>🔴 이 규칙의 <b>단일 출처</b>다. {@link com.pms.service.S3ImageStorageService} 가 저장값을 만들 때와
         * {@link ImageStorageProperties#resolvePublicImageHost()} 가 가져오기 허용 호스트를 정할 때 <b>같은 값</b>을
         * 봐야 한다 — 한쪽만 고치면 우리가 올린 사진을 우리가 못 가져오는 상태가 된다(2609_68 에서 실제로 발생).</p>
         */
        public String resolveBaseUrl() {
            if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                return publicBaseUrl;
            }
            if (bucket == null || bucket.isBlank()) {
                return null;
            }
            return "https://" + bucket + ".s3." + region + ".amazonaws.com";
        }
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
     * 우리 저장소가 이미지를 내주는 호스트. 저장소가 s3 일 때만 값이 있고 local 이면 {@code null} 이다.
     *
     * <p>마켓 URL 가져오기(2609_67 {@code addImagesFromUrls})의 허용 호스트 판정에 쓴다 — 마켓이 돌려주는
     * 사진 주소에는 <b>우리가 올려서 보낸 사진</b>(대표 사진의 {@code vendorPath}, 우리가 만든 상세 HTML 의
     * {@code <img src>})이 섞여 있어, 쿠팡 CDN 만 허용하면 화면에는 보이는데 가져올 수는 없는 사진이 생긴다.</p>
     *
     * <p>⚠️ 게터 이름({@code getXxx})을 쓰지 않는다 — {@code @ConfigurationProperties} 바인딩이 설정 키로
     * 넘겨다보지 않게 하기 위함이다.</p>
     */
    public String resolvePublicImageHost() {
        if (!"s3".equalsIgnoreCase(type)) {
            return null;
        }
        String base = s3.resolveBaseUrl();
        if (base == null) {
            return null;
        }
        try {
            return URI.create(base).toURL().getHost();
        } catch (Exception e) {
            return null;
        }
    }

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
