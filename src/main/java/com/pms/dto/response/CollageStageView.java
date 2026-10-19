package com.pms.dto.response;

import com.pms.service.collage.CollageSource;
import com.pms.service.collage.CollageStage;

/**
 * One stage and the collage it currently uses (FEATURE_2610_21 D40 · D41).
 *
 * @param stage      MASTER · SELLER · LISTING
 * @param sellerId   SELLER / LISTING stage: the seller; MASTER: null
 * @param sellerName SELLER / LISTING stage: the seller name; MASTER: null
 * @param listingId  LISTING stage: the listing; otherwise null
 * @param platform   LISTING stage: the listing platform name ({@code COUPANG} …); otherwise null
 * @param source     the stored stage this stage uses — OWN · SELLER · MASTER · NONE
 * @param enabled    whether that stored stage is switched on; null when {@code source} is NONE
 */
public record CollageStageView(
        CollageStage stage,
        Long sellerId,
        String sellerName,
        Long listingId,
        String platform,
        CollageSource source,
        Boolean enabled) {
}
