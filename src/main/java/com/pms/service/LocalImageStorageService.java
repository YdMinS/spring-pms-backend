package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.exception.ImageStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Disk-based {@link ImageStorageService} for local/test profiles.
 *
 * <p>Active only when {@code image.storage.type=local} (default, {@code matchIfMissing=true}).
 * Behaviour is unchanged from the pre-S3 implementation, except {@code uploadImage} now returns
 * the disk-relative path ({@code uploadDir + "/" + filename}) rather than the bare filename —
 * absorbing the URL assembly the controller used to do, so {@code product.imageUrl} carries a
 * consistent contract across implementations.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "image.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorageService implements ImageStorageService {

    private final ImageStorageProperties properties;
    private final ImageValidator imageValidator;

    @Override
    public String uploadImage(MultipartFile file, Long productId) {
        // 1. Validate file
        imageValidator.validate(file);

        // 2. Create upload directory if not exists
        Path uploadPath = Paths.get(properties.getUploadDir());
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            log.error("Failed to create upload directory: {}", uploadPath, e);
            throw new ImageStorageException("Failed to create upload directory", e);
        }

        // 3. Generate unique filename
        String originalExtension = getFileExtension(file.getOriginalFilename());
        String generatedFilename;
        if (productId != null) {
            generatedFilename = String.format("product_%d_%d_%s.%s",
                    productId,
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8),
                    originalExtension);
        } else {
            generatedFilename = String.format("%d_%s.%s",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8),
                    originalExtension);
        }

        // 4. Save file to disk
        Path filePath = uploadPath.resolve(generatedFilename);
        try {
            file.transferTo(filePath);
            log.info("Image uploaded successfully: {}", generatedFilename);
        } catch (IOException e) {
            log.error("Failed to upload image: {}", generatedFilename, e);
            throw new ImageStorageException("Failed to upload image", e);
        }

        // 5. Return disk-relative path (imageUrl contract)
        return properties.getUploadDir() + "/" + generatedFilename;
    }

    @Override
    public byte[] getImage(String filename) throws FileNotFoundException {
        // 1. Extract filename only (remove path prefix if present)
        String extractedFilename = extractFilename(filename);

        // 2. Construct full path
        Path filePath = Paths.get(properties.getUploadDir(), extractedFilename);

        // 3. Check if file exists
        if (!Files.exists(filePath)) {
            log.warn("Image file not found: {}", extractedFilename);
            throw new FileNotFoundException("Image file not found: " + extractedFilename);
        }

        // 4. Read file content
        try {
            byte[] content = Files.readAllBytes(filePath);
            log.debug("Image retrieved successfully: {}", extractedFilename);
            return content;
        } catch (IOException e) {
            log.error("Failed to read image file: {}", extractedFilename, e);
            throw new ImageStorageException("Failed to read image file", e);
        }
    }

    @Override
    public String uploadBytes(byte[] data, String category, String filename, String contentType) {
        // Store under {uploadDir}/{category}/{filename}; return the disk-relative path (stored value).
        Path categoryPath = Paths.get(properties.getUploadDir(), category);
        try {
            Files.createDirectories(categoryPath);
            Path target = categoryPath.resolve(filename);
            Files.write(target, data);
            log.info("Bytes stored: {}", target);
        } catch (IOException e) {
            log.error("Failed to store bytes: {}/{}", category, filename, e);
            throw new ImageStorageException("Failed to store bytes", e);
        }
        return properties.getUploadDir() + "/" + category + "/" + filename;
    }

    @Override
    public byte[] getBytes(String storedValue) throws FileNotFoundException {
        // storedValue is the disk-relative path returned by uploadBytes/uploadImage.
        Path filePath = Paths.get(storedValue);
        if (!Files.exists(filePath)) {
            log.warn("Stored file not found: {}", storedValue);
            throw new FileNotFoundException("Stored file not found: " + storedValue);
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read stored file: {}", storedValue, e);
            throw new ImageStorageException("Failed to read stored file", e);
        }
    }

    @Override
    public void deleteImage(String filename) {
        // Extract filename only so a stored path/URL resolves against uploadDir
        String extractedFilename = extractFilename(filename);
        Path filePath = Paths.get(properties.getUploadDir(), extractedFilename);

        // Delete file gracefully (no error if missing)
        try {
            if (Files.deleteIfExists(filePath)) {
                log.info("Image deleted successfully: {}", extractedFilename);
            } else {
                log.debug("Image file not found for deletion: {}", extractedFilename);
            }
        } catch (IOException e) {
            log.warn("Failed to delete image file: {}", extractedFilename, e);
            // Don't throw - handle gracefully per requirement
        }
    }

    /**
     * Extract filename only from full path (removes directory prefix if present).
     */
    private String extractFilename(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.contains("/")) {
            return path.substring(path.lastIndexOf("/") + 1);
        }
        return path;
    }

    /**
     * Extract file extension from filename.
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
