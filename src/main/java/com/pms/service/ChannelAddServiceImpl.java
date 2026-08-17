package com.pms.service;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.response.BatchChannelAddResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Channel add (FEATURE_2608_06 / 15). See {@link ChannelAddService}.
 *
 * <p>{@code addChannel} copies <em>all</em> of the master's options (the master is the single option
 * universe — no subset selection) into a new DRAFT cell, then reuses the {@link ListingAssetService#regenerateAssets}
 * seam. It runs as {@code REQUIRES_NEW} so the copy + regenerate are atomic per cell <em>and</em> so the batch
 * path ({@link #addChannelsBatch}) gets per-cell transaction isolation: each target commits or rolls back
 * independently, and one failure never marks a shared transaction rollback-only.</p>
 *
 * <p>Options are copied with a placeholder {@code sellingPrice} (the NOT-NULL column is filled by regenerate
 * before commit) and a null {@code platformOptionId} (issued by 3c).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelAddServiceImpl implements ChannelAddService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAddServiceImpl.class);

    private final MasterProductRepository masterProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final SellerRepository sellerRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final ListingAssetService listingAssetService;

    /**
     * Self proxy so the batch loop reaches {@link #addChannel} through the {@code REQUIRES_NEW} advice (a direct
     * self-invocation would bypass the proxy → no new transaction per cell → no partial-success isolation).
     */
    @Autowired
    @Lazy
    private ChannelAddService self;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // The master is the single option universe → copy all of its options (no subset selection).
        List<MasterProductOption> masterOptions = masterProductOptionRepository.findByMasterProductId(masterProductId);
        if (masterOptions.isEmpty()) {
            throw new IllegalArgumentException("옵션 없는 마스터");
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

        List<Long> optionIds = masterOptions.stream().map(MasterProductOption::getId).collect(Collectors.toList());
        Map<Long, List<MasterProductOptionItem>> itemsByOption = masterProductOptionItemRepository
                .findByOptionIdIn(optionIds).stream()
                .collect(Collectors.groupingBy(it -> it.getOption().getId()));

        for (MasterProductOption masterOption : masterOptions) {
            ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                    .productListing(cell)
                    .optionName(masterOption.getName())
                    .sellingPrice(BigDecimal.ZERO)    // placeholder; regenerate fills the real price below
                    .platformOptionId(null)           // issued by 3c
                    .build());
            for (MasterProductOptionItem item : itemsByOption.getOrDefault(masterOption.getId(), List.of())) {
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

    /**
     * ⚠️ NOT_SUPPORTED: the class default is {@code @Transactional(readOnly=true)}, but the batch orchestrator
     * must run with no surrounding transaction so each {@link #addChannel} opens its own {@code REQUIRES_NEW}
     * boundary. A single wrapping transaction would go rollback-only on the first caught exception and block the
     * later successful commits (no partial success). This method just loops and aggregates.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BatchChannelAddResponse addChannelsBatch(Long masterProductId, BatchChannelAddRequest request) {
        List<BatchChannelAddResponse.Result> results = new ArrayList<>();
        int succeeded = 0, failed = 0;
        for (BatchChannelAddRequest.Target target : request.getTargets()) {
            ChannelAddRequest single = ChannelAddRequest.builder()
                    .sellerId(target.getSellerId()).platform(target.getPlatform()).build();
            try {
                // Through the self proxy → REQUIRES_NEW: independent commit per cell.
                ChannelAddResponse added = self.addChannel(masterProductId, single);
                results.add(BatchChannelAddResponse.Result.builder()
                        .sellerId(target.getSellerId()).platform(target.getPlatform())
                        .success(true).productListingId(added.getProductListingId()).build());
                succeeded++;
            } catch (Exception e) {
                // Isolate: a failed target must not abort the remaining targets.
                results.add(BatchChannelAddResponse.Result.builder()
                        .sellerId(target.getSellerId()).platform(target.getPlatform())
                        .success(false).errorMessage(e.getMessage()).build());
                failed++;
                log.warn("[CHANNEL-ADD-BATCH] masterId={} sellerId={} platform={} add failed: {}",
                        masterProductId, target.getSellerId(), target.getPlatform(), e.getMessage());
            }
        }
        return BatchChannelAddResponse.builder()
                .requested(request.getTargets().size())
                .succeeded(succeeded).failed(failed).results(results).build();
    }
}
