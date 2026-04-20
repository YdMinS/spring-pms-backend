package com.pms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageStorageService {
    @Value("${upload.path}")
    private String uploadPath;

    @Value("${upload.allowed-types}")
    private String allowedTypes;

    @Value("${upload.max-size:20971520}")
    private long maxFileSize;

    public String saveImage(MultipartFile file, Long productId) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }

        validateImageFile(file);

        String extension = getFileExtension(file.getOriginalFilename());
        String filename = productId + "_" + UUID.randomUUID() + "." + extension;
        String relativePath = "/images/products/" + filename;

        Path uploadDir = Paths.get(uploadPath, "images", "products");
        Files.createDirectories(uploadDir);

        Path filePath = uploadDir.resolve(filename);
        Files.write(filePath, file.getBytes());

        log.info("Image saved successfully at: {}", filePath);
        return relativePath;
    }

    public void deleteImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        try {
            Path filePath = Paths.get(uploadPath + imageUrl);
            Files.deleteIfExists(filePath);
            log.info("Image deleted successfully: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to delete image: {}", imageUrl, e);
        }
    }

    public void validateImageFile(MultipartFile file) {
        String contentType = file.getContentType();
        List<String> allowed = Arrays.asList(allowedTypes.split(","));

        boolean isAllowed = allowed.stream()
                .anyMatch(type -> contentType != null && contentType.contains(type.trim()));

        if (!isAllowed) {
            throw new IllegalArgumentException("Invalid file type. Allowed types: " + allowedTypes);
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("File size exceeds maximum limit of " + (maxFileSize / 1024 / 1024) + "MB");
        }
    }

    public MediaType detectMediaType(String imageUrl) {
        String extension = getFileExtension(imageUrl).toLowerCase();

        return switch (extension) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.valueOf("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
