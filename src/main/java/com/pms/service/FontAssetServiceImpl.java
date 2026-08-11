package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.dto.response.FontAssetResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.FontAssetRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Font listing/upload/delete. {@link FontAsset} is NOT {@code @TenantId} (system fonts share
 * {@code tenantId=null}), so tenant scoping is enforced explicitly here:
 * <ul>
 *   <li>{@link #list()} → system ∪ current tenant (repository query).</li>
 *   <li>{@link #upload} → stamps the current tenant id.</li>
 *   <li>{@link #delete} → refuses system fonts (400) and other tenants' fonts (404).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FontAssetServiceImpl implements FontAssetService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("ttf", "otf");
    private static final long MAX_FONT_BYTES = 5L * 1024 * 1024; // 5MB

    private final FontAssetRepository fontAssetRepository;
    private final ImageStorageService imageStorageService;

    @Override
    public List<FontAssetResponse> list() {
        return fontAssetRepository.findSystemAndTenant(TenantContext.get()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public FontAssetResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Font file is required");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extension(original);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only .ttf/.otf fonts are allowed");
        }
        if (file.getSize() > MAX_FONT_BYTES) {
            throw new IllegalArgumentException("Font file exceeds 5MB limit");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context for font upload");
        }

        String filename = "font_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read font upload", e);
        }
        String contentType = file.getContentType() != null ? file.getContentType() : "font/" + extension;
        String storageKey = imageStorageService.uploadBytes(bytes, "fonts", filename, contentType);

        String displayName = stripExtension(original.isBlank() ? filename : original);
        FontAsset saved = fontAssetRepository.save(FontAsset.builder()
                .tenantId(tenantId)
                .displayName(displayName)
                .familyKey(displayName)
                .source(FontSource.UPLOADED)
                .storageKey(storageKey)
                .build());
        log.info("Font uploaded (tenant {}): {}", tenantId, saved.getId());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        FontAsset font = fontAssetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FontAsset", id));
        if (font.getTenantId() == null) {
            throw new IllegalArgumentException("System fonts cannot be deleted");
        }
        if (!font.getTenantId().equals(TenantContext.get())) {
            // Belongs to another tenant → behave as not found (no cross-tenant delete).
            throw new ResourceNotFoundException("FontAsset", id);
        }
        imageStorageService.deleteImage(font.getStorageKey());
        fontAssetRepository.delete(font);
    }

    private FontAssetResponse toResponse(FontAsset f) {
        return FontAssetResponse.builder()
                .id(f.getId())
                .displayName(f.getDisplayName())
                .familyKey(f.getFamilyKey())
                .source(f.getSource())
                .system(f.getTenantId() == null)
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
