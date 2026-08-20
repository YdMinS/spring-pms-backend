package com.pms.service;

import com.pms.dto.response.MasterProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Manages a {@link com.pms.domain.MasterProduct}'s image pool and its field mappings (FEATURE_2608_06 / 37).
 *
 * <p>Every image is uploaded into the pool first ({@link #uploadToPool}); it is then mapped — by drag/select
 * — onto detail zones ({@link #setZoneImages}) and/or the cover photo ({@link #setSourceImage}). One pool
 * image can be reused across several fields (M:N via {@code MasterImageZoneAssignment}).</p>
 *
 * <p>All operations resolve the master tenant-scoped first ({@code MasterProductRepository.findScopedById},
 * cross-tenant/absent → 404). Images live in S3 (DB stores only the URL).</p>
 */
public interface MasterProductImageService {

    /** Upload one image into the master's pool (no zone binding); appended after the current max sortOrder. */
    MasterProductImageResponse uploadToPool(Long masterId, MultipartFile file);

    /**
     * Import product image slots into the pool as reference entries (live-links, no copy — FEATURE_2608_06 / 40).
     * Each slot's owning product is tenant-verified; returns the refreshed pool.
     */
    List<MasterProductImageResponse> importProductImages(Long masterId, List<Long> productImageIds);

    /** All pool images with their mapping state (assignedZones + isSource). */
    List<MasterProductImageResponse> listPool(Long masterId);

    /** Replace a detail zone's mapped images with exactly {@code imageIds} (ordered; empty clears the zone). */
    List<MasterProductImageResponse> setZoneImages(Long masterId, String zoneId, List<Long> imageIds);

    /** Set the cover photo to {@code imageId} (single mapping), or clear it when {@code imageId} is null. */
    MasterProductImageResponse setSourceImage(Long masterId, Long imageId);

    /** Remove a pool image: clear its mappings, delete the row, then best-effort delete storage. */
    void removeFromPool(Long masterId, Long imageId);
}
