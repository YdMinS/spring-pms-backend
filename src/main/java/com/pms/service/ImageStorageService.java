package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.exception.ImageStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * ImageStorageService - Handles file I/O operations for product images
 *
 * Responsibilities:
 * - Upload images with validation
 * - Generate unique filenames
 * - Create storage directories
 * - Retrieve image content
 * - Delete images gracefully
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    private final ImageStorageProperties properties;
    private final ImageValidator imageValidator;

    /**
     * Upload image file and return generated filename (without product ID)
     *
     * @param file Image file to upload
     * @return Generated filename (not full path)
     * @throws InvalidImageException if validation fails
     * @throws ImageStorageException if storage fails
     */
    public String uploadImage(MultipartFile file) {
        return uploadImage(file, null);
    }

    /**
     * Upload image file and return generated filename (with product ID)
     *
     * @param file Image file to upload
     * @param productId Product ID for filename generation (optional)
     * @return Generated filename (not full path)
     * @throws InvalidImageException if validation fails
     * @throws ImageStorageException if storage fails
     */
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

        // 5. Return generated filename
        return generatedFilename;
    }

    /**
     * Retrieve image content as bytes
     *
     * @param filename Filename to retrieve
     * @return Image file content
     * @throws FileNotFoundException if file doesn't exist
     * @throws ImageStorageException if read fails
     */
    public byte[] getImage(String filename) throws FileNotFoundException {
        // 1. Construct full path
        Path filePath = Paths.get(properties.getUploadDir(), filename);

        // 2. Check if file exists
        if (!Files.exists(filePath)) {
            log.warn("Image file not found: {}", filename);
            throw new FileNotFoundException("Image file not found: " + filename);
        }

        // 3. Read file content
        try {
            byte[] content = Files.readAllBytes(filePath);
            log.debug("Image retrieved successfully: {}", filename);
            return content;
        } catch (IOException e) {
            log.error("Failed to read image file: {}", filename, e);
            throw new ImageStorageException("Failed to read image file", e);
        }
    }

    /**
     * Delete image file gracefully (no exception if missing)
     *
     * @param filename Filename to delete
     */
    public void deleteImage(String filename) {
        // 1. Construct full path
        Path filePath = Paths.get(properties.getUploadDir(), filename);

        // 2. Delete file gracefully (no error if missing)
        try {
            if (Files.deleteIfExists(filePath)) {
                log.info("Image deleted successfully: {}", filename);
            } else {
                log.debug("Image file not found for deletion: {}", filename);
            }
        } catch (IOException e) {
            log.warn("Failed to delete image file: {}", filename, e);
            // Don't throw - handle gracefully per requirement
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
