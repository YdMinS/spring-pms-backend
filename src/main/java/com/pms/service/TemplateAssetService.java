package com.pms.service;

import com.pms.dto.response.TemplateAssetResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Tenant-owned thumbnail asset library: list / upload / delete reusable fixed images. Mirrors
 * {@link FontAssetService} but {@link com.pms.domain.TemplateAsset} is {@code @TenantId} (no system rows),
 * so scoping is automatic. Implementation: {@link TemplateAssetServiceImpl}.
 */
public interface TemplateAssetService {

    /** Current tenant's assets, newest first. */
    List<TemplateAssetResponse> list();

    /** Validate (jpeg/png) + store an uploaded image; returns the persisted asset. */
    TemplateAssetResponse upload(MultipartFile file);

    /** Rename the asset's display name (own tenant only; other tenants' ids behave as not-found). */
    TemplateAssetResponse rename(Long id, String name);

    /** Delete the asset (own tenant only; other tenants' ids behave as not-found). */
    void delete(Long id);
}
