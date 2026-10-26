package com.pms.dto.response;

import com.pms.service.ThumbnailPhotoSource;
import com.pms.service.collage.CollageStage;

/**
 * One stage and the thumbnail photo it currently uses (FEATURE_2610_32 D14 · D31 · D32).
 *
 * @param stage        MASTER · SELLER · LISTING
 * @param sellerId     SELLER / LISTING stage: the seller; MASTER: null
 * @param sellerName   SELLER / LISTING stage: the seller name; MASTER: null
 * @param listingId    LISTING stage: the listing; otherwise null
 * @param platform     LISTING stage: the listing platform name ({@code COUPANG} …); otherwise null
 * @param source       where the photo comes from (six values)
 * @param imageId      OWN · SELLER · MASTER: the chosen pool image · COVER: the cover pool image · otherwise null
 * @param imageUrl     effective URL of {@code imageId}; null when {@code imageId} is null
 * @param collageUsed  a switched-on collage resolves for this stage (FEATURE_2610_21 D37 · D42 · D3), so the
 *                     thumbnail is the collage and the photo is used only once the collage is switched off (D6)
 */
public record ThumbnailPhotoStageView(
        CollageStage stage,
        Long sellerId,
        String sellerName,
        Long listingId,
        String platform,
        ThumbnailPhotoSource source,
        Long imageId,
        String imageUrl,
        boolean collageUsed) {
}
