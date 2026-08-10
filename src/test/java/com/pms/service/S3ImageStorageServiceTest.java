package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S3ImageStorageServiceTest - Unit tests for the S3-based ImageStorageService (S3Client mocked).
 *
 * MUST-KEEP behaviours: tenant-scoped key + public URL, no ACL, missing-tenant guard,
 * key extraction on delete, unsupported getImage. No real S3 access.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3ImageStorageService - Unit Tests (S3Client mocked)")
class S3ImageStorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private ImageValidator imageValidator;

    @Captor
    private ArgumentCaptor<PutObjectRequest> putCaptor;

    @Captor
    private ArgumentCaptor<DeleteObjectRequest> deleteCaptor;

    private S3ImageStorageService service;

    @BeforeEach
    void setUp() {
        ImageStorageProperties properties = new ImageStorageProperties();
        properties.setType("s3");
        ImageStorageProperties.S3 s3 = new ImageStorageProperties.S3();
        s3.setBucket("test-bucket");
        s3.setRegion("ap-northeast-2");
        s3.setKeyPrefix("tenants");
        s3.setPublicBaseUrl(null); // computed
        properties.setS3(s3);
        service = new S3ImageStorageService(s3Client, properties, imageValidator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockMultipartFile jpg() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }

    @Test
    @DisplayName("uploadImage builds tenant-scoped key and returns public URL")
    void uploadImage_buildsTenantScopedKeyAndReturnsPublicUrl() {
        // Given
        TenantContext.set(7L);

        // When
        String returned = service.uploadImage(jpg(), 42L);

        // Then
        verify(s3Client, times(1)).putObject(putCaptor.capture(), any(RequestBody.class));
        PutObjectRequest req = putCaptor.getValue();
        assertThat(req.bucket()).isEqualTo("test-bucket");
        assertThat(req.key()).startsWith("tenants/7/products/product_42_");
        assertThat(req.key()).endsWith(".jpg");
        assertThat(returned)
                .isEqualTo("https://test-bucket.s3.ap-northeast-2.amazonaws.com/" + req.key());
    }

    @Test
    @DisplayName("uploadImage does not set an ACL (BucketOwnerEnforced)")
    void uploadImage_noAclSet() {
        // Given
        TenantContext.set(7L);

        // When
        service.uploadImage(jpg(), 42L);

        // Then
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().acl()).isNull();
    }

    @Test
    @DisplayName("uploadImage throws when TenantContext is missing")
    void uploadImage_missingTenantContext_throws() {
        // Given
        TenantContext.clear();

        // When & Then
        assertThatThrownBy(() -> service.uploadImage(jpg(), 42L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("deleteImage extracts the key from the public URL")
    void deleteImage_extractsKeyFromUrl() {
        // Given
        String imageUrl =
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/tenants/7/products/foo.jpg";

        // When
        service.deleteImage(imageUrl);

        // Then
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().key()).isEqualTo("tenants/7/products/foo.jpg");
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("test-bucket");
    }

    @Test
    @DisplayName("getImage is unsupported (served via public URL)")
    void getImage_unsupported() {
        assertThatThrownBy(() -> service.getImage("anything"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
