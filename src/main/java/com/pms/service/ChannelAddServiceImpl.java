package com.pms.service;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.exception.DuplicateChannelException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Channel add (FEATURE_2608_06 / 3b'). See {@link ChannelAddService}.
 *
 * <p>⚠️ {@code addChannel} is a single {@code @Transactional} so the copy (listing + options + BOM) and the
 * reused {@link ListingAssetService#regenerateAssets} seam are atomic — a regenerate failure (e.g. missing
 * margin/template, 400) rolls the whole cell back. Options are copied with a placeholder
 * {@code sellingPrice} (the NOT-NULL column is filled by regenerate before commit) and a null
 * {@code platformOptionId} (issued by 3c).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelAddServiceImpl implements ChannelAddService {

    private final MasterProductRepository masterProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final SellerRepository sellerRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final ListingAssetService listingAssetService;

    @Override
    @Transactional
    public ChannelAddResponse addChannel(Long masterProductId, ChannelAddRequest request) {
        // --- validation (MUST-KEEP) ---
        MasterProduct master = masterProductRepository.findScopedById(masterProductId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterProductId));
        if (Boolean.FALSE.equals(master.getActive())) {
            throw new IllegalArgumentException("비활성 마스터");
        }

        if (productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(
                masterProductId, request.getSellerId(), request.getPlatform())) {
            throw new DuplicateChannelException();
        }

        // Selected master options must all belong to this master (subset allowed).
        Map<Long, MasterProductOption> optionsById = masterProductOptionRepository
                .findByMasterProductId(masterProductId).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, o -> o));
        for (Long optionId : request.getOptionIds()) {
            if (!optionsById.containsKey(optionId)) {
                throw new IllegalArgumentException("마스터 옵션 아님");
            }
        }

        Seller seller = sellerRepository.findById(request.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.getSellerId()));

        // Category/delivery/box are now derived from the master (13). Pre-validate the master has a category for
        // this platform before creating the cell (missing → 400 "카테고리 미설정"); delivery/box are checked by the
        // reused regenerate seam. The cell's own category/delivery/package columns stay null (deprecated).
        masterChannelConfigService.resolveCategory(masterProductId, request.getPlatform());

        // --- copy: master options → listing options + BOM ---
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .masterProduct(master)
                .seller(seller)
                .platform(request.getPlatform())
                .platformProductId(null)              // no market id until 3c push
                .name(master.getName())
                .status(ListingStatus.DRAFT)
                .build());

        Map<Long, List<MasterProductOptionItem>> itemsByOption = masterProductOptionItemRepository
                .findByOptionIdIn(request.getOptionIds()).stream()
                .collect(Collectors.groupingBy(it -> it.getOption().getId()));

        for (Long optionId : request.getOptionIds()) {
            MasterProductOption masterOption = optionsById.get(optionId);
            ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                    .productListing(cell)
                    .optionName(masterOption.getName())
                    .sellingPrice(BigDecimal.ZERO)    // placeholder; regenerate fills the real price below
                    .platformOptionId(null)           // issued by 3c
                    .build());
            for (MasterProductOptionItem item : itemsByOption.getOrDefault(optionId, List.of())) {
                productListingProductRepository.save(ProductListingProduct.builder()
                        .productListingOption(listingOption)
                        .product(item.getProduct())
                        .quantity(item.getQuantity())
                        .build());
            }
        }
        // Flush so the reused seam reads the copied options/BOM.
        productListingRepository.flush();

        // --- auto-generate (reuse 3b-2 seam; margin/box/delivery unset → 400 rolls back) ---
        listingAssetService.regenerateAssets(cell);

        return ChannelAddResponse.builder()
                .productListingId(cell.getId())
                .status(ListingStatus.DRAFT.name())
                .generated(listingAssetService.getGenerated(cell.getId()))
                .build();
    }
}
