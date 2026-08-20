package com.pms.service;

import com.pms.domain.MasterProductImage;
import org.springframework.stereotype.Component;

/**
 * The single service-layer point that resolves a {@link MasterProductImage}'s <b>effective URL</b>
 * (FEATURE_2608_06 / 40).
 *
 * <p>A pool entry is either a <b>reference</b> ({@code productImage != null}, live-links a product slot) or
 * <b>edited</b> ({@code imageUrl != null}, master-owned). The effective URL is
 * {@code productImage != null ? productImage.getImageUrl() : imageUrl}.</p>
 *
 * <p>⚠️ Must be called inside a transaction — {@code entry.getProductImage()} is LAZY, so resolving outside
 * the tx/tenant boundary would fail. All single/derived read paths ({@code listPool},
 * {@code ListingAssetService.resolveBaseImage}, the 08 detail generator) go through this component; do NOT
 * inline the ternary elsewhere. The only exception is the batch zone finder
 * ({@code findZoneImageUrlsByMasterIds}), which resolves the same priority with a JPQL {@code COALESCE}.</p>
 */
@Component
public class ProductImageUrlResolver {

    /** {@code productImage.imageUrl} for a reference entry (live), else the edited entry's own imageUrl. */
    public String resolve(MasterProductImage entry) {
        return entry.getProductImage() != null
                ? entry.getProductImage().getImageUrl()
                : entry.getImageUrl();
    }
}
