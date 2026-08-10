package com.pms.migration;

import com.pms.config.ImageStorageProperties;
import com.pms.domain.Product;
import com.pms.repository.ProductRepository;
import com.pms.service.S3ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link LocalToS3ImageMigrationRunner} (one behaviour each).
 *
 * <p>The guard-#1 no-op path (no S3 bean) is out of scope — {@code getIfAvailable()} always returns
 * the mock S3 here so the runner proceeds.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocalToS3ImageMigrationRunner - Unit Tests")
class LocalToS3ImageMigrationRunnerTest {

    @Mock private ProductRepository productRepository;
    @Mock private ImageStorageProperties properties;
    @Mock private ObjectProvider<S3ImageStorageService> s3StorageProvider;
    @Mock private S3ImageStorageService s3;

    private LocalToS3ImageMigrationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new LocalToS3ImageMigrationRunner(productRepository, properties, s3StorageProvider);
        // Guard passes: S3 storage active.
        given(s3StorageProvider.getIfAvailable()).willReturn(s3);
        // Single tenant to iterate.
        given(productRepository.findDistinctTenantIds()).willReturn(List.of(1L));
    }

    @Test
    @DisplayName("imageUrl already https → uploadExisting/save not called")
    void migrate_skipsAlreadyHttpUrl() {
        Product alreadyOnS3 = Product.builder()
                .id(1L)
                .imageUrl("https://bucket.s3.ap-northeast-2.amazonaws.com/tenants/1/products/foo.jpg")
                .build();
        given(productRepository.findAll()).willReturn(List.of(alreadyOnS3));

        runner.run();

        verify(s3, never()).uploadExisting(any(), any());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("local-path imageUrl + file present → uploads and rewrites imageUrl")
    void migrate_uploadsLocalPathAndRewritesUrl(@org.junit.jupiter.api.io.TempDir Path uploadDir)
            throws Exception {
        Files.writeString(uploadDir.resolve("foo.jpg"), "img-bytes");
        given(properties.getUploadDir()).willReturn(uploadDir.toString());

        Product local = Product.builder()
                .id(1L)
                .imageUrl("/app/uploads/products/foo.jpg")
                .build();
        given(productRepository.findAll()).willReturn(List.of(local));

        String newUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/tenants/1/products/foo.jpg";
        given(s3.uploadExisting(any(), any())).willReturn(newUrl);

        runner.run();

        verify(s3).uploadExisting(any(), any());
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo(newUrl);
    }

    @Test
    @DisplayName("local-path imageUrl but file missing → no save, no exception, continues")
    void migrate_missingFile_countsAndContinues(@org.junit.jupiter.api.io.TempDir Path uploadDir) {
        // uploadDir is empty → resolved file does not exist.
        given(properties.getUploadDir()).willReturn(uploadDir.toString());

        Product local = Product.builder()
                .id(1L)
                .imageUrl("/app/uploads/products/missing.jpg")
                .build();
        given(productRepository.findAll()).willReturn(List.of(local));

        assertThatNoException().isThrownBy(runner::run);

        verify(s3, never()).uploadExisting(any(), any());
        verify(productRepository, never()).save(any());
    }
}
