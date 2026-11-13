package com.pms.service.listing;

import com.pms.domain.ListingMarketValueGroup;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingMarketValuesResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.MasterChannelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Default {@link ListingMarketValueService}. See the interface for the rules. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingMarketValueServiceImpl implements ListingMarketValueService {

    static final String NOT_IN_USE = "마켓 값을 쓰지 않는 묶음입니다";
    static final String OWN_CATEGORY_FIRST =
            "자기 카테고리를 쓰는 판매채널은 [마스터 카테고리로 변경]을 쓰세요 — 속성·고시도 함께 마스터 값으로 바뀝니다";

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ListingChannelResolver resolver;
    private final ListingAssetService listingAssetService;
    private final TagMergeService tagMergeService;
    private final MasterChannelConfigService masterChannelConfigService;
    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Override
    @Transactional
    public Set<ListingMarketValueGroup> applyImported(Long listingId, ImportedProduct fetched) {
        ProductListing cell = requireScopedCell(listingId);
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Set<ListingMarketValueGroup> groups = resolver.resolve(cell.getPlatform()).marketValueGroups();

        boolean attributesOn = groups.contains(ListingMarketValueGroup.ATTRIBUTES)
                && fetched.options().stream()
                        .anyMatch(o -> !o.attributes().isEmpty() || !o.notices().isEmpty());
        List<String> tags = fetched.tags() == null ? List.of() : fetched.tags();
        boolean tagsOn = groups.contains(ListingMarketValueGroup.TAGS) && !tags.isEmpty();
        boolean shippingOn = groups.contains(ListingMarketValueGroup.SHIPPING) && !fetched.shipping().isEmpty();

        if (attributesOn) {
            // Only the first market option paired with a cell option is used; an unpaired cell option stays as it is.
            List<ProductListingOption> paired = new ArrayList<>();
            List<ProductListingOption> updated = new ArrayList<>();
            for (ImportedProduct.Option read : fetched.options()) {
                ProductListingOption option = MarketOptionMatch.find(options, read.vendorItemId(), read.itemName());
                if (option == null || paired.stream().anyMatch(p -> p == option)) {
                    continue;
                }
                paired.add(option);
                updated.add(option.toBuilder()
                        .categoryAttributes(read.attributes().isEmpty() ? null : read.attributes())
                        .categoryNotices(read.notices().isEmpty() ? null : read.notices())
                        .build());
            }
            if (!updated.isEmpty()) {
                productListingOptionRepository.saveAll(updated);
            }
        }

        ProductListing.ProductListingBuilder next = cell.toBuilder()
                .usesMarketAttributes(attributesOn)
                .usesMarketTags(tagsOn)
                .usesMarketShipping(shippingOn);
        if (attributesOn && fetched.noticeGroup() != null && !fetched.noticeGroup().isBlank()) {
            next.categoryNoticeGroup(fetched.noticeGroup());
        }
        if (tagsOn) {
            next.tags(tagMergeService.dedup(tags));
        }
        productListingRepository.save(next.build());

        if (shippingOn) {
            // The shipping save re-reads the cell and filters the keys — the one place of that rule.
            Map<String, String> override = cell.getShippingOverride() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(cell.getShippingOverride());
            override.putAll(fetched.shipping());
            listingAssetService.updateShippingOverride(listingId, override);
        }

        Set<ListingMarketValueGroup> on = EnumSet.noneOf(ListingMarketValueGroup.class);
        if (attributesOn) {
            on.add(ListingMarketValueGroup.ATTRIBUTES);
        }
        if (tagsOn) {
            on.add(ListingMarketValueGroup.TAGS);
        }
        if (shippingOn) {
            on.add(ListingMarketValueGroup.SHIPPING);
        }
        return on;
    }

    @Override
    @Transactional
    public ListingMarketValuesResponse useMasterValues(Long listingId, ListingMarketValueGroup group) {
        ProductListing cell = requireScopedCell(listingId);
        if (!cell.usesMarketValues(group)) {
            throw new IllegalArgumentException(NOT_IN_USE);
        }

        ProductListing.ProductListingBuilder next = cell.toBuilder();
        switch (group) {
            case ATTRIBUTES -> {
                if (usesOwnCategory(cell)) {
                    throw new IllegalArgumentException(OWN_CATEGORY_FIRST);
                }
                List<ProductListingOption> cleared = new ArrayList<>();
                for (ProductListingOption option : productListingOptionRepository.findByProductListingId(listingId)) {
                    if (option.getCategoryAttributes() == null && option.getCategoryNotices() == null) {
                        continue;
                    }
                    cleared.add(option.toBuilder()
                            .categoryAttributes(null)
                            .categoryNotices(null)
                            .build());
                }
                if (!cleared.isEmpty()) {
                    productListingOptionRepository.saveAll(cleared);
                }
                next.usesMarketAttributes(false).categoryNoticeGroup(null);
            }
            case TAGS -> next.usesMarketTags(false).tags(null);
            case SHIPPING -> next.usesMarketShipping(false).shippingOverride(null);
        }
        return ListingMarketValuesResponse.of(productListingRepository.save(next.build()));
    }

    @Override
    @Transactional
    public BackfillOutcome backfillFromMarket(Long listingId) {
        Optional<ProductListing> found = productListingRepository.findScopedById(listingId);
        if (found.isEmpty()) {
            return BackfillOutcome.NOT_FOUND;
        }
        ProductListing cell = found.get();
        if (cell.isUsesMarketAttributes() || cell.isUsesMarketTags() || cell.isUsesMarketShipping()) {
            return BackfillOutcome.ALREADY_ON;
        }
        if (!StringUtils.hasText(cell.getPlatformProductId())) {
            throw new IllegalArgumentException(ListingMarketSyncServiceImpl.NOT_ON_MARKET);
        }
        MarketProductAccess.requireSupported(cell.getPlatform());
        MarketplaceAccount account = MarketProductAccess.requireActiveAccount(cell.getSeller().getId(),
                cell.getPlatform(), sellerRepository, marketplaceAccountRepository).account();
        ImportedProduct fetched = resolver.resolve(cell.getPlatform())
                .fetchProduct(cell.getPlatformProductId(), account);

        // Only a blank 「등록상품명」 is filled; every other cell value stays as it is (D21).
        if (!StringUtils.hasText(cell.getRegistrationName()) && StringUtils.hasText(fetched.productName())) {
            productListingRepository.save(cell.toBuilder()
                    .registrationName(fetched.productName().trim())
                    .build());
        }
        applyImported(listingId, fetched);
        return BackfillOutcome.FILLED;
    }

    /** A cell whose category cannot be resolved does not use its own category (R7). */
    private boolean usesOwnCategory(ProductListing cell) {
        try {
            return masterChannelConfigService.resolveChannelCategory(cell).own();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ProductListing requireScopedCell(Long listingId) {
        return productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
    }
}
