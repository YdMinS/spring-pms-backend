package com.pms.service.collage;

import com.pms.dto.request.CollagePreviewRequest;
import com.pms.dto.request.CollageSaveRequest;
import com.pms.dto.response.CollageOverviewResponse;
import com.pms.dto.response.CollageStageResponse;

/**
 * Collage stages of one master (FEATURE_2610_21 D9 · D20 · D40~D42 · D46). All methods resolve the master
 * tenant-scoped first (404 when absent).
 *
 * <p>Stage arguments: {@code MASTER} ignores {@code sellerId}/{@code listingId}; {@code SELLER} needs a seller that
 * has a listing of this master (400 otherwise); {@code LISTING} needs a listing of this master (404 otherwise).</p>
 *
 * <p>⚠️ Saving never regenerates a thumbnail (D20) — the listing's [저장 후 재생성] applies it.</p>
 */
public interface ThumbnailCollageService {

    /** Every stage of the master and the collage it currently uses (D40 — the master-tab entry). */
    CollageOverviewResponse overview(Long masterId);

    /** One stage for the editor (D41 · D43 · D46). */
    CollageStageResponse getStage(Long masterId, CollageStage stage, Long sellerId, Long listingId);

    /** Store this stage's own collage (enabled) or switch the collage off here (D42). */
    CollageStageResponse save(Long masterId, CollageStage stage, Long sellerId, Long listingId,
                              CollageSaveRequest request);

    /** Delete this stage's own row — the stage then uses the upper stage (D21 · D42). Absent row = no-op. */
    CollageStageResponse delete(Long masterId, CollageStage stage, Long sellerId, Long listingId);

    /** Preview JPEG of an unsaved collage — nothing is stored or uploaded (D20 · D35 · D46). */
    byte[] preview(Long masterId, CollagePreviewRequest request);
}
