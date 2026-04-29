package com.pms.service;

import com.pms.config.ImageStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.pms.fixture.ProductTestFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ImageStorageServiceTest - Unit tests for ImageStorageService
 *
 * Phase 3-1 TDD RED: Tests file upload, retrieval, deletion operations
 * All 7 tests FAIL (RED phase - no implementation yet)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImageStorageService - Unit Tests (file storage operations)")
public class ImageStorageServiceTest {

    private ImageStorageService imageStorageService;
    private ImageValidator imageValidator;
    private ImageStorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ImageStorageProperties();
        imageValidator = new ImageValidator(properties);
        imageStorageService = new ImageStorageService(properties, imageValidator);
    }

    // ==================== Test 1: Upload image successfully ====================

    @Test
    @DisplayName("Should upload image successfully and return filename")
    void testUploadImage_Success() {
        // Given
        MockMultipartFile imageFile = createMockImageFile();

        // When
        String filename = imageStorageService.uploadImage(imageFile);

        // Then
        assertThat(filename).isNotBlank();
        assertThat(filename).endsWith(".jpg");
    }

    // ==================== Test 2: Creates upload directory if not exists ====================

    @Test
    @DisplayName("Should create upload directory if it doesn't exist")
    void testUploadImage_CreatesDirectory() {
        // Given
        MockMultipartFile imageFile = createMockImageFile();
        Path uploadDir = Paths.get("uploads", "products");

        // When
        imageStorageService.uploadImage(imageFile);

        // Then
        assertThat(uploadDir).exists();
    }

    // ==================== Test 3: Generates unique filename ====================

    @Test
    @DisplayName("Should generate unique filename with timestamp/UUID")
    void testUploadImage_GeneratesUniqueFilename() {
        // Given
        MockMultipartFile file1 = createMockImageFile();
        MockMultipartFile file2 = createMockImageFile();

        // When
        String filename1 = imageStorageService.uploadImage(file1);
        String filename2 = imageStorageService.uploadImage(file2);

        // Then
        assertThat(filename1).isNotEqualTo(filename2);
    }

    // ==================== Test 4: Get image successfully ====================

    @Test
    @DisplayName("Should retrieve image content as bytes")
    void testGetImage_Success() throws Exception {
        // Given
        MockMultipartFile imageFile = createMockImageFile();
        String filename = imageStorageService.uploadImage(imageFile);

        // When
        byte[] content = imageStorageService.getImage(filename);

        // Then
        assertThat(content).isNotNull();
        assertThat(content).isNotEmpty();
    }

    // ==================== Test 5: Get non-existent image ====================

    @Test
    @DisplayName("Should throw FileNotFoundException when image doesn't exist")
    void testGetImage_NotFound_ThrowsException() {
        // Given
        String nonexistentFilename = "nonexistent-image-12345.jpg";

        // When & Then
        assertThatThrownBy(() -> imageStorageService.getImage(nonexistentFilename))
                .isInstanceOf(FileNotFoundException.class);
    }

    // ==================== Test 6: Delete image successfully ====================

    @Test
    @DisplayName("Should delete image successfully from disk")
    void testDeleteImage_Success() {
        // Given
        MockMultipartFile imageFile = createMockImageFile();
        String filename = imageStorageService.uploadImage(imageFile);

        // When
        imageStorageService.deleteImage(filename);

        // Then
        assertThatThrownBy(() -> imageStorageService.getImage(filename))
                .isInstanceOf(FileNotFoundException.class);
    }

    // ==================== Test 7: Delete non-existent image gracefully ====================

    @Test
    @DisplayName("Should handle deletion of non-existent image gracefully")
    void testDeleteImage_FileNotFound_HandleGracefully() {
        // Given
        String nonexistentFilename = "nonexistent-image-12345.jpg";

        // When & Then - should not throw exception
        assertThat(nonexistentFilename).isNotBlank();
        imageStorageService.deleteImage(nonexistentFilename);
        // Test passes if no exception is thrown
    }
}
