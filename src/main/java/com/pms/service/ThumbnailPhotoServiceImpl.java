package com.pms.service;

import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailCollage;
import com.pms.domain.ThumbnailPhoto;
import com.pms.dto.request.ThumbnailPhotoRequest;
import com.pms.dto.response.ThumbnailPhotoStageView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ThumbnailPhotoRepository;
import com.pms.service.collage.CollageScopes;
import com.pms.service.collage.CollageStage;
import com.pms.service.collage.CollageStageTarget;
import com.pms.service.collage.CollageStageTargets;
import com.pms.service.collage.ThumbnailCollageResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ThumbnailPhotoService} (FEATURE_2610_32). Stage arguments are checked by {@link CollageStageTargets} (D23);
 * the stage order is {@link ThumbnailPhotoResolver#chain} (D4), shared with generation; {@code collageUsed} follows
 * the collage resolution rule (stored row along the same chain, switched on, 2+ components — D31).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThumbnailPhotoServiceImpl implements ThumbnailPhotoService {

    private final MasterProductRepository masterProductRepository;
    private final MasterProductImageRepository masterProductImageRepository;
    private final MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    private final ThumbnailPhotoRepository thumbnailPhotoRepository;
    private final ThumbnailPhotoResolver thumbnailPhotoResolver;
    private final ThumbnailCollageResolver thumbnailCollageResolver;
    private final CollageStageTargets collageStageTargets;
    private final ProductImageUrlResolver productImageUrlResolver;
    private final MasterProductService masterProductService;

    @Override
    public List<ThumbnailPhotoStageView> stages(Long masterId) {
        return stageViews(requireMaster(masterId));
    }

    @Override
    @Transactional
    public List<ThumbnailPhotoStageView> update(Long masterId, ThumbnailPhotoRequest request) {
        MasterProduct master = requireMaster(masterId);
        CollageStageTarget target = collageStageTargets.resolve(master.getId(), request.getStage(),
                request.getSellerId(), request.getListingId());
        ThumbnailPhoto existing = thumbnailPhotoRepository
                .findByMasterProduct_IdAndScopeKey(master.getId(), target.scopeKey()).orElse(null);
        if (request.getImageId() == null) {
            if (existing != null) {
                thumbnailPhotoRepository.delete(existing);
                thumbnailPhotoRepository.flush();
            }
            return stageViews(master);
        }
        // D7: only a photo of this master's own pool (same message as the cover-photo choice).
        MasterProductImage image = masterProductImageRepository.findById(request.getImageId())
                .filter(img -> img.getMasterProduct().getId().equals(master.getId()))
                .orElseThrow(() -> new IllegalArgumentException("이미지 목록 불일치"));
        thumbnailPhotoRepository.save(existing != null
                ? existing.toBuilder().image(image).build()
                : ThumbnailPhoto.builder()
                        .masterProduct(master)
                        .seller(target.stage() == CollageStage.SELLER ? target.seller() : null)
                        .productListing(target.listing())
                        .scopeKey(target.scopeKey())
                        .image(image)
                        .build());
        return stageViews(master);
    }

    // ---------------------------------------------------------------- helpers

    private MasterProduct requireMaster(Long masterId) {
        return masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));
    }

    private List<ThumbnailPhotoStageView> stageViews(MasterProduct master) {
        Long masterId = master.getId();
        Map<String, ThumbnailPhoto> photos = thumbnailPhotoResolver.rowsByScopeKey(masterId);
        Map<String, ThumbnailCollage> collages = thumbnailCollageResolver.rowsByScopeKey(masterId);
        boolean bundle = masterProductService.isBundle(masterId);
        List<MasterImageZoneAssignment> cover = masterImageZoneAssignmentRepository
                .findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(
                        masterId, MasterImageZoneAssignment.SOURCE_ZONE);
        MasterProductImage coverImage = cover.isEmpty() ? null : cover.get(0).getImage();
        boolean legacyUpload = StringUtils.hasText(master.getSourceImageUrl());
        Fallback fallback = new Fallback(photos, collages, bundle, coverImage, legacyUpload);

        List<ThumbnailPhotoStageView> out = new ArrayList<>();
        out.add(view(fallback, CollageStage.MASTER, null, null));
        for (Map.Entry<Seller, List<ProductListing>> entry
                : collageStageTargets.listingsBySeller(masterId).entrySet()) {
            Seller seller = entry.getKey();
            out.add(view(fallback, CollageStage.SELLER, seller, null));
            for (ProductListing listing : entry.getValue()) {
                out.add(view(fallback, CollageStage.LISTING, seller, listing));
            }
        }
        return out;
    }

    /** The master-wide values every stage row is computed from. */
    private record Fallback(Map<String, ThumbnailPhoto> photos, Map<String, ThumbnailCollage> collages,
                            boolean bundle, MasterProductImage coverImage, boolean legacyUpload) {
    }

    private ThumbnailPhotoStageView view(Fallback f, CollageStage stage, Seller seller, ProductListing listing) {
        Long sellerId = seller == null ? null : seller.getId();
        Long listingId = listing == null ? null : listing.getId();
        List<String> keys = ThumbnailPhotoResolver.chain(stage, sellerId, listingId);
        ThumbnailPhotoSource source;
        MasterProductImage image;
        ThumbnailPhoto row = ThumbnailPhotoResolver.first(f.photos(), keys);
        if (row != null) {
            image = row.getImage();
            if (row.getScopeKey().equals(keys.get(0))) {
                source = ThumbnailPhotoSource.OWN;
            } else if (CollageScopes.MASTER.equals(row.getScopeKey())) {
                source = ThumbnailPhotoSource.MASTER;
            } else {
                source = ThumbnailPhotoSource.SELLER;
            }
        } else if (f.coverImage() != null) {
            image = f.coverImage();
            source = ThumbnailPhotoSource.COVER;
        } else {
            image = null;
            source = f.legacyUpload() ? ThumbnailPhotoSource.LEGACY_UPLOAD : ThumbnailPhotoSource.FIRST_COMPONENT;
        }
        ThumbnailCollage collage = ThumbnailPhotoResolver.first(f.collages(), keys);
        boolean collageUsed = f.bundle() && collage != null && Boolean.TRUE.equals(collage.getEnabled())
                && collage.getLayout() != null;
        return new ThumbnailPhotoStageView(
                stage,
                sellerId,
                seller == null ? null : seller.getSellerName(),
                listingId,
                listing == null || listing.getPlatform() == null ? null : listing.getPlatform().name(),
                source,
                image == null ? null : image.getId(),
                image == null ? null : productImageUrlResolver.resolve(image),
                collageUsed);
    }
}
