package com.pms.service;

import com.pms.dto.request.ThumbnailPhotoRequest;
import com.pms.dto.response.ThumbnailPhotoStageView;

import java.util.List;

/**
 * Thumbnail photo stages of one master (FEATURE_2610_32 D5 · D7 · D23 · D31). Both methods resolve the master
 * tenant-scoped first (404 when absent).
 *
 * <p>Stage list order = MASTER, then per seller (ascending id) its SELLER row followed by that seller's LISTING rows
 * (ascending id) — the collage overview order.</p>
 *
 * <p>⚠️ Choosing a photo never regenerates a thumbnail (D9) — the listing's [저장 후 재생성] applies it.</p>
 */
public interface ThumbnailPhotoService {

    /** Every stage of the master and the photo it currently uses (D14 · D31 · D32). */
    List<ThumbnailPhotoStageView> stages(Long masterId);

    /** Store ({@code imageId}) or clear (null) one stage's own photo, then return {@link #stages}. */
    List<ThumbnailPhotoStageView> update(Long masterId, ThumbnailPhotoRequest request);
}
