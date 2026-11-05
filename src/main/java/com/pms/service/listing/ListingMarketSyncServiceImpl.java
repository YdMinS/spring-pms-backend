package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketImage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.MarketSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.ListingMarketImageRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.MarketImageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link ListingMarketSyncService} implementation. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingMarketSyncServiceImpl implements ListingMarketSyncService {

    /** The guidance when a market photo could not be copied into our storage (exact wording). */
    static final String PHOTO_COPY_FAILED = "마켓 사진을 내려받지 못했습니다";
    static final String NOT_ON_MARKET = "마켓에 올라가지 않은 판매상품입니다";

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ListingMarketImageRepository marketImageRepository;
    private final ListingChannelResolver resolver;
    private final MarketImageStore marketImageStore;
    private final ImageStorageService imageStorageService;

    @Override
    @Transactional
    public MarketSyncResponse syncFromMarket(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        if (!StringUtils.hasText(cell.getPlatformProductId())) {
            throw new IllegalArgumentException(NOT_ON_MARKET);
        }
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        ImportedProduct imported = resolver.resolve(cell.getPlatform())
                .fetchProduct(cell.getPlatformProductId(), account);
        return apply(cell, imported);
    }

    @Override
    @Transactional
    public MarketSyncResponse applyImported(Long listingId, ImportedProduct imported) {
        return apply(requireScopedCell(listingId), imported);
    }

    private MarketSyncResponse apply(ProductListing cell, ImportedProduct imported) {
        // 1. Names (D3 · D4 · D19). A blank market display name keeps ours (NOT NULL column).
        String displayName = imported.displayName();
        ProductListing saved = productListingRepository.save(cell.toBuilder()
                .name(StringUtils.hasText(displayName) ? displayName.trim() : cell.getName())
                .registrationName(StringUtils.hasText(imported.productName()) ? imported.productName().trim() : null)
                .build());

        GeneratedProductData existing = generatedProductDataRepository
                .findByProductListingId(saved.getId()).orElse(null);
        GeneratedProductData.GeneratedProductDataBuilder data = existing != null ? existing.toBuilder()
                : GeneratedProductData.builder().productListing(saved)
                        .source(GeneratedContentSource.AUTO).thumbnailSource(GeneratedContentSource.AUTO);
        boolean dataChanged = false;

        // 2. Detail (D2 · D23) — the market HTML as it is; photo addresses inside are not downloaded.
        if (StringUtils.hasText(imported.detailHtml())) {
            data.detailHtml(imported.detailHtml()).source(GeneratedContentSource.MARKET);
            dataChanged = true;
        }

        // 3. Photos (D8 · D21 · D35 · D36 · D37 · D38).
        String imageNotice = imported.imageNotice();
        Set<String> stale = new LinkedHashSet<>();
        if (imageNotice == null && !imported.marketImages().isEmpty()) {
            Copied copied = copyAll(saved, imported.marketImages());
            if (copied == null) {
                imageNotice = PHOTO_COPY_FAILED;
            } else {
                for (ListingMarketImage old : marketImageRepository.findByProductListingIdOrderBySortOrderAsc(
                        saved.getId())) {
                    stale.add(old.getImageUrl());
                }
                if (copied.thumbnailUrl() != null && existing != null && existing.getThumbnailUrl() != null) {
                    stale.add(existing.getThumbnailUrl());
                }
                stale.removeAll(copied.urls());
                marketImageRepository.deleteByProductListingId(saved.getId());
                marketImageRepository.saveAll(copied.rows());
                if (copied.thumbnailUrl() != null) {
                    data.thumbnailUrl(copied.thumbnailUrl()).thumbnailSource(GeneratedContentSource.MARKET);
                    dataChanged = true;
                }
            }
        }
        if (dataChanged) {
            generatedProductDataRepository.save(data.generatedAt(LocalDateTime.now()).build());
        }
        // Best-effort drop of the superseded copies — never rolls back (same rule as the thumbnail override).
        stale.forEach(this::bestEffortDelete);
        return new MarketSyncResponse(saved.getName(), saved.getRegistrationName(), imageNotice);
    }

    /**
     * Copy every market photo once (an address repeated across options is copied once). The photos of a market option
     * that matches no option of this listing are not stored — the thumbnail may still come from one (D38). null when
     * a copy failed; the copies made so far are dropped.
     */
    private Copied copyAll(ProductListing cell, List<ImportedProduct.MarketImage> images) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        Map<String, String> copyByMarketUrl = new HashMap<>();
        List<ListingMarketImage> rows = new ArrayList<>();
        String thumbnailUrl = null;
        try {
            for (ImportedProduct.MarketImage image : images) {
                Long optionId = null;
                if (image.itemIndex() != null) {
                    ProductListingOption option = matchOption(options, image);
                    if (option == null && (thumbnailUrl != null || !image.representative())) {
                        continue;
                    }
                    optionId = option == null ? null : option.getId();
                    if (option == null) {
                        // Only the thumbnail needs this photo — copy it without storing a row.
                        thumbnailUrl = copyOnce(copyByMarketUrl, image.url(), cell.getId());
                        continue;
                    }
                }
                String copy = copyOnce(copyByMarketUrl, image.url(), cell.getId());
                if (thumbnailUrl == null && image.representative()) {
                    thumbnailUrl = copy;
                }
                rows.add(ListingMarketImage.builder()
                        .productListing(cell)
                        .productListingOptionId(optionId)
                        .representative(image.representative())
                        .marketImageType(image.marketImageType())
                        .sortOrder(rows.size())
                        .imageUrl(copy)
                        .build());
            }
        } catch (IllegalArgumentException e) {
            log.warn("Market photo copy failed for listing {}: {}", cell.getId(), e.getMessage());
            copyByMarketUrl.values().forEach(this::bestEffortDelete);
            return null;
        }
        return new Copied(rows, thumbnailUrl, Set.copyOf(copyByMarketUrl.values()));
    }

    private String copyOnce(Map<String, String> copyByMarketUrl, String marketUrl, Long listingId) {
        String done = copyByMarketUrl.get(marketUrl);
        if (done != null) {
            return done;
        }
        String copy = marketImageStore.copy(marketUrl, listingId, copyByMarketUrl.size());
        copyByMarketUrl.put(marketUrl, copy);
        return copy;
    }

    /**
     * The listing option a market option's photo belongs to: the Coupang option id first, then the exact name (the same
     * axes as [승인 새로고침], 2609_22/D21). No fuzzy matching.
     */
    private static ProductListingOption matchOption(List<ProductListingOption> options,
                                                    ImportedProduct.MarketImage image) {
        if (image.vendorItemId() != null) {
            for (ProductListingOption option : options) {
                if (image.vendorItemId().equals(option.getPlatformOptionId())) {
                    return option;
                }
            }
        }
        if (image.itemName() != null) {
            for (ProductListingOption option : options) {
                if (image.itemName().equals(option.getOptionName())) {
                    return option;
                }
            }
        }
        return null;
    }

    private ProductListing requireScopedCell(Long id) {
        return productListingRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
    }

    private void bestEffortDelete(String url) {
        try {
            imageStorageService.deleteImage(url);
        } catch (Exception e) {
            log.warn("Best-effort market photo storage delete failed: {}", url, e);
        }
    }

    /** The copied set: rows to store, the thumbnail copy (may be null) and every copy made. */
    private record Copied(List<ListingMarketImage> rows, String thumbnailUrl, Set<String> urls) {
    }
}
