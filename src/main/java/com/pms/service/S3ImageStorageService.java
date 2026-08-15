package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * S3-based {@link ImageStorageService} for dev/prod profiles.
 *
 * <p>Active only when {@code image.storage.type=s3}. Uploads to a public-read bucket and returns
 * the object's public https URL (persisted as {@code product.imageUrl}) so marketplace bots
 * (Coupang/Naver) can fetch images without authentication.</p>
 *
 * <p>Keys are tenant-scoped: {@code {keyPrefix}/{tenantId}/products/{filename}}. The tenant id is
 * read from {@link TenantContext}, populated by the JWT filter — uploads are authenticated ADMIN
 * web requests, so it is always present.</p>
 *
 * <p>⚠️ ACL is never set on {@code PutObjectRequest}: buckets use Object Ownership
 * {@code BucketOwnerEnforced} (ACLs disabled), where setting an ACL fails. Public read is granted
 * by the bucket policy, provisioned out-of-band (see PLAN §10).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "image.storage", name = "type", havingValue = "s3")
public class S3ImageStorageService implements ImageStorageService {

    private final S3Client s3Client;
    private final ImageStorageProperties properties;
    private final ImageValidator imageValidator;

    @Override
    public String uploadImage(MultipartFile file, Long productId) {
        // 1. Validate (same checks as Local)
        imageValidator.validate(file);

        // 2. Generate unique filename (same rule as Local)
        String extension = getFileExtension(file.getOriginalFilename());
        String filename;
        if (productId != null) {
            filename = String.format("product_%d_%d_%s.%s",
                    productId, System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8), extension);
        } else {
            filename = String.format("%d_%s.%s",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8), extension);
        }

        // 3. Tenant-scoped key
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context for S3 upload — uploads require an authenticated request");
        }
        String key = buildKey(tenantId, filename);

        // 4. Put object (no ACL — BucketOwnerEnforced bucket)
        ImageStorageProperties.S3 s3 = properties.getS3();
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3.getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read upload stream for S3 put", e);
        }
        log.info("Image uploaded to S3: {}", key);

        // 5. Return public URL
        return publicUrl(key);
    }

    /**
     * Migration-only: upload an existing on-disk file into the current {@link TenantContext} scope
     * and return its public URL. Skips {@link ImageValidator} (the file was already validated when
     * first stored). The filename is derived from {@code file}; key/URL rules match {@link #uploadImage}
     * (same {@link #buildKey}/{@link #publicUrl} helpers), so migrated objects are indistinguishable
     * from freshly-uploaded ones.
     *
     * <p>⚠️ Requires the migration runner to have set the tenant on {@link TenantContext} first
     * (non-web/batch context — see {@code LocalToS3ImageMigrationRunner}).</p>
     */
    public String uploadExisting(Path file, String contentType) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context for S3 migration upload — set TenantContext before uploadExisting");
        }
        String filename = file.getFileName().toString();
        String key = buildKey(tenantId, filename);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromFile(file));
        log.info("Existing image migrated to S3: {}", key);

        return publicUrl(key);
    }

    @Override
    public String uploadBytes(byte[] data, String category, String filename, String contentType) {
        // Tenant-scoped key: {keyPrefix}/{tenantId}/{category}/{filename}. Public read via bucket policy.
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context for S3 uploadBytes — requires an authenticated request");
        }
        String key = properties.getS3().getKeyPrefix() + "/" + tenantId + "/" + category + "/" + filename;
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
        log.info("Bytes uploaded to S3: {}", key);
        return publicUrl(key);
    }

    @Override
    public byte[] getBytes(String storedValue) {
        // storedValue is a public URL → extract key → getObject.
        String key = extractKey(storedValue);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Cannot extract S3 key from stored value: " + storedValue);
        }
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .build()).asByteArray();
    }

    @Override
    public void deleteImage(String imageUrl) {
        String key = extractKey(imageUrl);
        if (key == null || key.isEmpty()) {
            log.debug("No S3 key extracted from imageUrl, skipping delete: {}", imageUrl);
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getBucket())
                    .key(key)
                    .build());
            log.info("Image deleted from S3: {}", key);
        } catch (RuntimeException e) {
            // Graceful, matching Local's delete policy
            log.warn("Failed to delete S3 object: {}", key, e);
        }
    }

    @Override
    public byte[] getImage(String imageUrlOrFilename) throws FileNotFoundException {
        throw new UnsupportedOperationException("S3 images are served via public URL");
    }

    private String buildKey(Long tenantId, String filename) {
        return properties.getS3().getKeyPrefix() + "/" + tenantId + "/products/" + filename;
    }

    /**
     * Single source of the stored public URL for a key. Reused by {@link #uploadImage} and
     * {@link #uploadExisting} so freshly-uploaded and migrated objects share one URL rule.
     */
    private String publicUrl(String key) {
        return publicBaseUrl() + "/" + key;
    }

    /**
     * Public base URL: override if configured, else computed from bucket + region.
     */
    private String publicBaseUrl() {
        ImageStorageProperties.S3 s3 = properties.getS3();
        if (s3.getPublicBaseUrl() != null && !s3.getPublicBaseUrl().isBlank()) {
            return s3.getPublicBaseUrl();
        }
        return "https://" + s3.getBucket() + ".s3." + s3.getRegion() + ".amazonaws.com";
    }

    /**
     * Extract the S3 key from a stored imageUrl. Strips the public base prefix; defensively
     * also handles any {@code .amazonaws.com/} host prefix (e.g. CDN vs direct URL drift).
     */
    private String extractKey(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        String base = publicBaseUrl() + "/";
        if (imageUrl.startsWith(base)) {
            return imageUrl.substring(base.length());
        }
        int hostIdx = imageUrl.indexOf(".amazonaws.com/");
        if (hostIdx >= 0) {
            return imageUrl.substring(hostIdx + ".amazonaws.com/".length());
        }
        return null;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
