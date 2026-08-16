package com.pms.service;

import com.pms.dto.response.MasterProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Manages a {@link com.pms.domain.MasterProduct}'s input images (zone members) — the source images for
 * detail-page {@code imageZone} blocks (FEATURE_2608_06 / Step 2-1).
 *
 * <p>All operations resolve the master tenant-scoped first ({@code MasterProductRepository.findScopedById},
 * cross-tenant/absent → 404). Images live in S3 (DB stores only the URL). Immutability is preserved via
 * {@code toBuilder} on reorder.</p>
 */
public interface MasterProductImageService {

    /** Upload one image into a zone; appended after the current max sortOrder. */
    MasterProductImageResponse upload(Long masterId, String zoneId, MultipartFile file);

    /** All images of a master, ordered by zone then position. */
    List<MasterProductImageResponse> list(Long masterId);

    /** Reorder a zone's images to match {@code imageIds} exactly (set + size must match). */
    List<MasterProductImageResponse> reorder(Long masterId, String zoneId, List<Long> imageIds);

    /** Delete one image belonging to the master (best-effort storage delete). */
    void delete(Long masterId, Long imageId);
}
