package com.pms.service.collage;

import com.pms.domain.ProductListing;
import com.pms.domain.Seller;

/**
 * A validated stage argument (FEATURE_2610_21 · FEATURE_2610_32 D23): the stage, its {@code scope_key}, and the
 * seller / listing it points at. Built only by {@link CollageStageTargets#resolve}.
 *
 * @param stage    MASTER · SELLER · LISTING
 * @param scopeKey {@link CollageScopes} key of the stage
 * @param seller   SELLER stage: that seller · LISTING stage: the listing's seller · MASTER: null
 * @param listing  LISTING stage: that listing · otherwise null
 */
public record CollageStageTarget(CollageStage stage, String scopeKey, Seller seller, ProductListing listing) {
}
