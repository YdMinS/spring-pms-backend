package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProductListing;
import com.pms.domain.ThumbnailPhoto;
import com.pms.repository.ThumbnailPhotoRepository;
import com.pms.service.collage.CollageScopes;
import com.pms.service.collage.CollageStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which stage thumbnail photo a listing's single-photo thumbnail uses (FEATURE_2610_32 D4 · D5).
 *
 * <p>Stage order = listing ({@code LISTING:{id}}) ?? its seller ({@code SELLER:{sellerId}}) ?? master
 * ({@code MASTER}) — the first stored row wins, the same order as the collage stages (FEATURE_2610_21 D37). No row
 * → empty, and {@code ListingAssetServiceImpl.resolveBaseImage} continues with the cover photo ?? legacy upload ??
 * first component.</p>
 *
 * <p>Usage: {@code thumbnailPhotoResolver.resolve(cell).ifPresent(image -> load(image));} ·
 * {@code ThumbnailPhotoResolver.first(rows, ThumbnailPhotoResolver.chain(stage, sellerId, listingId))}.</p>
 *
 * <p>❌ Do not build the stage order anywhere else — {@link #chain} is shared by generation and the stage status
 * (D31). File: {@code service/ThumbnailPhotoResolver.java}.</p>
 */
@Component
@RequiredArgsConstructor
public class ThumbnailPhotoResolver {

    private final ThumbnailPhotoRepository thumbnailPhotoRepository;

    /** The pool photo chosen for this listing's nearest stage, or empty (D4). */
    public Optional<MasterProductImage> resolve(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return Optional.empty();
        }
        ThumbnailPhoto row = first(rowsByScopeKey(master.getId()), chain(cell));
        return row == null ? Optional.empty() : Optional.of(row.getImage());
    }

    /** Every stored stage row of a master keyed by {@code scope_key}. */
    public Map<String, ThumbnailPhoto> rowsByScopeKey(Long masterId) {
        Map<String, ThumbnailPhoto> rows = new HashMap<>();
        for (ThumbnailPhoto row : thumbnailPhotoRepository.findByMasterProduct_Id(masterId)) {
            rows.put(row.getScopeKey(), row);
        }
        return rows;
    }

    /**
     * Stage keys from the stage itself upward: LISTING → listing, seller (when known), master · SELLER → seller,
     * master · MASTER → master.
     */
    public static List<String> chain(CollageStage stage, Long sellerId, Long listingId) {
        List<String> keys = new ArrayList<>();
        if (stage == CollageStage.LISTING) {
            keys.add(CollageScopes.listing(listingId));
        }
        if (stage != CollageStage.MASTER && sellerId != null) {
            keys.add(CollageScopes.seller(sellerId));
        }
        keys.add(CollageScopes.MASTER);
        return keys;
    }

    /** {@link #chain} of a listing. */
    public static List<String> chain(ProductListing cell) {
        return chain(CollageStage.LISTING, cell.getSeller() == null ? null : cell.getSeller().getId(), cell.getId());
    }

    /** The first stored row along {@code keys}, or null. */
    public static <T> T first(Map<String, T> rows, List<String> keys) {
        for (String key : keys) {
            T row = rows.get(key);
            if (row != null) {
                return row;
            }
        }
        return null;
    }
}
