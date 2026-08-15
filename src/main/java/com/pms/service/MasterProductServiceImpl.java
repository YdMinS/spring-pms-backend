package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.ListingMatrixResponse.MatrixCell;
import com.pms.dto.response.ListingMatrixResponse.MatrixRow;
import com.pms.dto.response.MasterProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Master product reads + channel coverage matrix (FEATURE_2608_06 / 3a).
 *
 * <p>The matrix is a code-synthesized LEFT JOIN: every marketplace account of the tenant (left) is
 * matched to the listing (if any) registered under the master for the same (seller, platform) key
 * (right). Built with 3 bounded queries to avoid N+1:</p>
 * <ol>
 *   <li>{@code findByMasterProductId} — the master's listings (right side)</li>
 *   <li>{@code findByProductListingIdIn} — those listings' options (selling prices), batched</li>
 *   <li>{@code findAllById} — seller display names, batched</li>
 * </ol>
 *
 * <p>Tenant safety: reads go through {@code findScopedById}/{@code findAll} which the {@code @TenantId}
 * filter scopes to the current tenant, so a cross-tenant master id yields 404 without a manual compare.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterProductServiceImpl implements MasterProductService {

    private final MasterProductRepository masterProductRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final SellerRepository sellerRepository;

    @Override
    public List<MasterProductResponse> getMasterProducts() {
        return masterProductRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public MasterProductResponse getMasterProduct(Long id) {
        return mapToResponse(requireScopedMaster(id));
    }

    @Override
    public ListingMatrixResponse getMatrix(Long id) {
        MasterProduct master = requireScopedMaster(id);

        // Right side: listings under this master (1 query), then their options batched (1 query).
        List<ProductListing> listings = productListingRepository.findByMasterProductId(id);
        List<Long> listingIds = listings.stream().map(ProductListing::getId).toList();
        Map<Long, BigDecimal> priceByListing = listingIds.isEmpty()
                ? Map.of()
                : productListingOptionRepository.findByProductListingIdIn(listingIds).stream()
                        .collect(Collectors.toMap(
                                o -> o.getProductListing().getId(),
                                o -> o.getSellingPrice(),
                                (first, dup) -> first));   // single SKU expected; keep first on dupes

        // Index listings by (sellerId|platform); first wins.
        Map<String, ProductListing> listingByKey = new LinkedHashMap<>();
        for (ProductListing pl : listings) {
            listingByKey.putIfAbsent(matchKey(pl.getSeller().getId(), pl.getPlatform()), pl);
        }

        // Left side: all accounts of the tenant + batched seller names (1 query).
        List<MarketplaceAccount> accounts = marketplaceAccountRepository.findAll();
        List<Long> sellerIds = accounts.stream()
                .map(a -> a.getSeller().getId())
                .distinct()
                .toList();
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName));

        List<MatrixRow> rows = accounts.stream().map(acc -> {
            Long sellerId = acc.getSeller().getId();
            ProductListing pl = listingByKey.get(matchKey(sellerId, acc.getPlatform()));
            MatrixCell cell = pl == null ? null : MatrixCell.builder()
                    .productListingId(pl.getId())
                    .platformProductId(pl.getPlatformProductId())
                    .sellingPrice(priceByListing.get(pl.getId()))
                    .build();
            return MatrixRow.builder()
                    .sellerId(sellerId)
                    .sellerName(sellerNames.get(sellerId))
                    .platform(acc.getPlatform())
                    .accountId(acc.getId())
                    .accountLabel(acc.getAccountAlias())
                    .registered(pl != null)
                    .cell(cell)
                    .build();
        }).toList();

        return ListingMatrixResponse.builder()
                .masterId(master.getId())
                .masterName(master.getName())
                .rows(rows)
                .build();
    }

    /** Tenant-scoped fetch; a cross-tenant/absent id yields 404 (findScopedById is @TenantId-filtered). */
    private MasterProduct requireScopedMaster(Long id) {
        return masterProductRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", id));
    }

    private static String matchKey(Long sellerId, String platform) {
        return sellerId + "|" + platform;
    }

    private MasterProductResponse mapToResponse(MasterProduct master) {
        return MasterProductResponse.builder()
                .id(master.getId())
                .name(master.getName())
                .build();
    }
}
