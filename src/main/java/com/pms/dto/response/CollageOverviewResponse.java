package com.pms.dto.response;

import java.util.List;

/**
 * The 「썸네일 콜라주」 block of the master 「이미지」 tab (FEATURE_2610_21 D40 — the master-tab entry).
 *
 * @param bundle   2+ components (D3) — false hides the block
 * @param products components, ascending id
 * @param stages   MASTER first, then per seller (ascending id) its SELLER stage followed by its listings (ascending id)
 */
public record CollageOverviewResponse(boolean bundle, List<CollageProductView> products, List<CollageStageView> stages) {
}
