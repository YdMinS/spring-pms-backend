package com.pms.service;

import com.pms.domain.TemplateAsset;
import com.pms.dto.response.TemplateAssetResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.TemplateAssetRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Thumbnail asset library. {@link TemplateAsset} IS {@code @TenantId} (no system/shared rows, unlike
 * {@link FontAssetServiceImpl}), so {@link #list()} is auto-scoped and {@link #upload} lets Hibernate
 * stamp the tenant. {@link #delete} still needs an explicit tenant guard because PK {@code findById} is
 * not tenant-filtered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateAssetServiceImpl implements TemplateAssetService {

    private final TemplateAssetRepository templateAssetRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    public List<TemplateAssetResponse> list() {
        return templateAssetRepository.findAllByOrderByIdDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public TemplateAssetResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Asset file is required");
        }
        // Reuse the product-image validator: jpeg/png, size, magic bytes, no traversal.
        imageValidator.validate(file);

        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context for asset upload");
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extension(original);
        String contentType = file.getContentType() != null ? file.getContentType() : "image/" + extension;
        String filename = "asset_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + "." + extension;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read asset upload", e);
        }
        String storageKey = imageStorageService.uploadBytes(bytes, "thumbnail-assets", filename, contentType);

        // tenantId is auto-set by @TenantId — do NOT set it in the builder.
        TemplateAsset saved = templateAssetRepository.save(TemplateAsset.builder()
                .name(stripExtension(original.isBlank() ? filename : original))
                .storageKey(storageKey)
                .contentType(contentType)
                .build());
        log.info("Template asset uploaded (tenant {}): {}", tenantId, saved.getId());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        TemplateAsset asset = templateAssetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateAsset", id));
        // PK findById is NOT tenant-filtered by @TenantId → explicit cross-tenant guard.
        if (!asset.getTenantId().equals(TenantContext.get())) {
            throw new ResourceNotFoundException("TemplateAsset", id);
        }
        imageStorageService.deleteImage(asset.getStorageKey()); // graceful (never throws on missing)
        templateAssetRepository.delete(asset);
    }

    private TemplateAssetResponse toResponse(TemplateAsset a) {
        return TemplateAssetResponse.builder()
                .id(a.getId())
                .name(a.getName())
                .storageKey(a.getStorageKey())
                .contentType(a.getContentType())
                .build();
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
