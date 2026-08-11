package com.pms.service;

import com.pms.dto.response.FontAssetResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FontAssetService {

    /** System (shared) ∪ current tenant fonts — editor dropdown source. */
    List<FontAssetResponse> list();

    /** Upload a .ttf/.otf (≤5MB) as a tenant-owned font. */
    FontAssetResponse upload(MultipartFile file);

    /** Delete a tenant-owned font. System fonts (tenantId null) cannot be deleted. */
    void delete(Long id);
}
