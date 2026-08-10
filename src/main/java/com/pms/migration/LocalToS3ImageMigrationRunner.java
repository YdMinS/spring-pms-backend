package com.pms.migration;

import com.pms.config.ImageStorageProperties;
import com.pms.domain.Product;
import com.pms.repository.ProductRepository;
import com.pms.security.TenantContext;
import com.pms.service.S3ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * One-off migration: move existing local-disk product images to S3 and rewrite
 * {@code product.imageUrl} to the public S3 URL.
 *
 * <p><b>Why a runner (not Liquibase)</b>: this moves binary files, not schema/data SQL, which
 * Liquibase cannot do. It is a gated {@link CommandLineRunner} — the bean only exists when
 * {@code image.migration.enabled=true}, so normal boots are unaffected. Turn the gate off again
 * once verified (re-running is idempotent but pointless).</p>
 *
 * <p><b>Non-web context</b>: at boot there is no SecurityContext/JWT, so {@link TenantContext} is
 * empty → {@code @TenantId} would filter every {@link Product} query to zero rows. This runner
 * therefore sets the tenant explicitly per tenant (restoring/clearing in {@code finally}), following
 * the non-web rule in backend CLAUDE.md §9.</p>
 *
 * <p><b>Idempotent &amp; non-destructive</b>: products whose {@code imageUrl} already starts with
 * {@code http} are skipped; original local files are never deleted (kept for verification/rollback).
 * Per-product failures are isolated so one bad row cannot abort the whole run.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "image.migration", name = "enabled", havingValue = "true")
public class LocalToS3ImageMigrationRunner implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final ImageStorageProperties properties;

    // Concrete S3 type via ObjectProvider: when image.storage.type != s3 no S3 bean exists, so a
    // hard dependency would fail context startup. ObjectProvider lets guard #1 no-op gracefully.
    private final ObjectProvider<S3ImageStorageService> s3StorageProvider;

    @Override
    public void run(String... args) {
        // Guard #1: S3 storage must be active — migrating to the local disk backend is meaningless.
        S3ImageStorageService s3 = s3StorageProvider.getIfAvailable();
        if (s3 == null) {
            log.warn("[image-migration] image.storage.type != s3 (no S3 bean) — skipping migration");
            return;
        }

        Counts total = new Counts();
        List<Long> tenantIds = productRepository.findDistinctTenantIds();
        log.info("[image-migration] starting for {} tenant(s)", tenantIds.size());

        for (Long tenantId : tenantIds) {
            Counts perTenant = new Counts();
            try {
                TenantContext.set(tenantId);
                for (Product product : productRepository.findAll()) {
                    migrateOne(s3, product, perTenant);
                }
            } finally {
                TenantContext.clear();
            }
            log.info("[image-migration] tenant {}: {}", tenantId, perTenant);
            total.add(perTenant);
        }

        log.info("[image-migration] DONE — {}", total);
    }

    private void migrateOne(S3ImageStorageService s3, Product product, Counts counts) {
        String url = product.getImageUrl();

        // Idempotent skip: nothing to migrate, or already an http(s) URL (S3 already).
        if (url == null || url.isBlank()
                || url.startsWith("http://") || url.startsWith("https://")) {
            counts.skippedAlreadyUrl++;
            return;
        }

        try {
            // Local path: take the filename (last path segment) and resolve under uploadDir.
            String filename = url.substring(url.lastIndexOf('/') + 1);
            Path path = Paths.get(properties.getUploadDir(), filename);

            if (!Files.exists(path)) {
                log.warn("[image-migration] missing local file for product {}: {}", product.getId(), path);
                counts.missingFile++;
                return;
            }

            String contentType = Files.probeContentType(path);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            String publicUrl = s3.uploadExisting(path, contentType);

            // Rewrite imageUrl only; toBuilder() copies every other field (no data loss) and honours
            // the entity no-@Setter rule. Original local file is intentionally left in place.
            Product updated = product.toBuilder().imageUrl(publicUrl).build();
            productRepository.save(updated);
            counts.migrated++;
        } catch (IOException | RuntimeException e) {
            log.error("[image-migration] failed for product {}: {}", product.getId(), url, e);
            counts.failed++;
        }
    }

    /** Mutable per-tenant / total tally. */
    private static final class Counts {
        int migrated;
        int skippedAlreadyUrl;
        int missingFile;
        int failed;

        void add(Counts o) {
            migrated += o.migrated;
            skippedAlreadyUrl += o.skippedAlreadyUrl;
            missingFile += o.missingFile;
            failed += o.failed;
        }

        @Override
        public String toString() {
            return "migrated=" + migrated + ", skippedAlreadyUrl=" + skippedAlreadyUrl
                    + ", missingFile=" + missingFile + ", failed=" + failed;
        }
    }
}
