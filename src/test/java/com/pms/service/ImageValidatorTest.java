package com.pms.service;

import com.pms.exception.InvalidImageException;
import com.pms.fixture.ProductTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ImageValidatorTest - Unit tests for ImageValidator
 *
 * Phase 3-1 TDD RED: Validates image file type, size, magic bytes, and security
 * All 8 tests FAIL (RED phase - no implementation yet)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImageValidator - Unit Tests (image validation)")
public class ImageValidatorTest {

    private ImageValidator imageValidator;

    @BeforeEach
    void setUp() {
        imageValidator = new ImageValidator();
    }

    // ==================== Test 1: Valid JPEG ====================

    @Test
    @DisplayName("Should pass validation for valid JPEG")
    void testValidate_ValidJpeg_Pass() {
        // Given
        MockMultipartFile validJpeg = ProductTestFixture.createMockImageFile();

        // When & Then
        assertThatNoException().isThrownBy(() ->
                imageValidator.validate(validJpeg)
        );
    }

    // ==================== Test 2: Valid PNG ====================

    @Test
    @DisplayName("Should pass validation for valid PNG")
    void testValidate_ValidPng_Pass() {
        // Given
        MockMultipartFile validPng = ProductTestFixture.createMockPngFile();

        // When & Then
        assertThatNoException().isThrownBy(() ->
                imageValidator.validate(validPng)
        );
    }

    // ==================== Test 3: File size exceeds 20MB ====================

    @Test
    @DisplayName("Should throw exception when file size exceeds 20MB")
    void testValidate_FileSizeExceeds20MB_ThrowsException() {
        // Given
        MockMultipartFile oversized = ProductTestFixture.createOversizedFile();

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(oversized))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("File size");
    }

    // ==================== Test 4: Invalid MIME type ====================

    @Test
    @DisplayName("Should throw exception for invalid MIME type (text/plain)")
    void testValidate_InvalidMimeType_ThrowsException() {
        // Given
        MockMultipartFile invalidType = ProductTestFixture.createInvalidTypeFile();

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(invalidType))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("MIME type");
    }

    // ==================== Test 5: Invalid file extension ====================

    @Test
    @DisplayName("Should throw exception for invalid file extension (.exe)")
    void testValidate_InvalidExtension_ThrowsException() {
        // Given
        MockMultipartFile invalidExtension = ProductTestFixture.createInvalidExtensionFile();

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(invalidExtension))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("extension");
    }

    // ==================== Test 6: Magic bytes don't match extension ====================

    @Test
    @DisplayName("Should throw exception when magic bytes don't match extension")
    void testValidate_MagicBytesInvalid_ThrowsException() {
        // Given - PNG file claiming to be JPEG
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile mismatchedBytes = new MockMultipartFile(
                "file",
                "fake.jpg",
                "image/jpeg",
                pngBytes
        );

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(mismatchedBytes))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("magic bytes");
    }

    // ==================== Test 7: Directory traversal attack ====================

    @Test
    @DisplayName("Should throw exception for directory traversal attack (../../etc/passwd)")
    void testValidate_DirectoryTraversal_ThrowsException() {
        // Given
        byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00};
        MockMultipartFile traversal = new MockMultipartFile(
                "file",
                "../../etc/passwd.jpg",
                "image/jpeg",
                jpegBytes
        );

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(traversal))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("path");
    }

    // ==================== Test 8: Empty file ====================

    @Test
    @DisplayName("Should throw exception for empty file")
    void testValidate_EmptyFile_ThrowsException() {
        // Given
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        // When & Then
        assertThatThrownBy(() -> imageValidator.validate(emptyFile))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("empty");
    }
}
