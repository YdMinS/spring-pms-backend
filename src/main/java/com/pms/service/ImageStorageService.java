package com.pms.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;

/**
 * ImageStorageService - abstraction over product-image storage.
 *
 * <p>Two implementations selected by {@code image.storage.type} property:</p>
 * <ul>
 *   <li>{@link LocalImageStorageService} — {@code type=local} (default). Disk storage;
 *       {@code uploadImage} returns a disk-relative path; {@code getImage} serves bytes.
 *       Used by local/test profiles.</li>
 *   <li>{@link S3ImageStorageService} — {@code type=s3}. Uploads to a public-read S3 bucket;
 *       {@code uploadImage} returns the public https URL; {@code getImage} is unsupported
 *       (images are served directly via the public URL). Used by dev/prod.</li>
 * </ul>
 *
 * <p>⚠️ The meaning of the value stored in {@code product.imageUrl} differs by environment
 * (relative path on local/test, full public URL on dev/prod). This is intentional — only
 * dev/prod need marketplace-bot-accessible URLs. {@code ProductController.getImage} branches on
 * whether the stored value starts with {@code http}.</p>
 */
public interface ImageStorageService {

    /**
     * Upload an image and return the value to persist in {@code product.imageUrl}.
     * Local = disk-relative path (e.g. {@code /app/uploads/products/product_1_...jpg}).
     * S3 = public URL (e.g. {@code https://bucket.s3.region.amazonaws.com/tenants/1/products/...jpg}).
     *
     * @param file      image file to upload (validated by {@link ImageValidator})
     * @param productId product id for filename generation (optional)
     * @return value to store in {@code product.imageUrl}
     */
    String uploadImage(MultipartFile file, Long productId);

    /** Convenience overload without a product id. */
    default String uploadImage(MultipartFile file) {
        return uploadImage(file, null);
    }

    /**
     * Delete an image by its stored {@code imageUrl} (path or URL). Graceful — never throws
     * if the target is missing.
     */
    void deleteImage(String imageUrlOrFilename);

    /**
     * Retrieve image bytes for proxy serving. Local only.
     *
     * @throws FileNotFoundException          if the file does not exist (Local)
     * @throws UnsupportedOperationException  always, for the S3 implementation (served via public URL)
     */
    byte[] getImage(String imageUrlOrFilename) throws FileNotFoundException;

    /**
     * Store arbitrary bytes under a category and return the value to persist (fonts, thumbnails …).
     * Used by the thumbnail engine (FEATURE_2608_05) — generated thumbnails and uploaded fonts.
     *
     * <p>Key layout differs by backend, mirroring {@link #uploadImage}:</p>
     * <ul>
     *   <li>Local: {@code {uploadDir}/{category}/{filename}} on disk → returns that disk-relative path.</li>
     *   <li>S3: {@code {keyPrefix}/{tenantId}/{category}/{filename}} → returns the public https URL
     *       (tenant read from {@link com.pms.security.TenantContext}; must be present).</li>
     * </ul>
     *
     * @param data        raw bytes to store
     * @param category    logical bucket, e.g. {@code thumbnails} or {@code fonts}
     * @param filename    target filename (caller ensures uniqueness / extension)
     * @param contentType MIME type (e.g. {@code image/jpeg}, {@code font/ttf})
     * @return value to persist (disk-relative path on Local, public URL on S3)
     */
    String uploadBytes(byte[] data, String category, String filename, String contentType);

    /**
     * Read back bytes previously stored via {@link #uploadBytes} (or {@link #uploadImage}), given the
     * exact value that method returned. Needed for server-side rendering of UPLOADED fonts, which must
     * load the actual font bytes regardless of backend.
     *
     * <ul>
     *   <li>Local: resolves the stored disk-relative path and reads it.</li>
     *   <li>S3: extracts the key from the public URL and {@code getObject}s it.</li>
     * </ul>
     *
     * @param storedValue the value returned by {@link #uploadBytes}/{@link #uploadImage}
     * @return the stored bytes
     * @throws FileNotFoundException if the target does not exist (Local)
     */
    byte[] getBytes(String storedValue) throws FileNotFoundException;
}
