package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Per-channel option selection (FEATURE_2608_06 / 42). See {@link ListingOptionService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingOptionServiceImpl implements ListingOptionService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;

    @Override
    public ListingOptionsResponse getOptions(Long listingId) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        // Read is never a resync trigger → needsResync = false.
        return ListingOptionsResponse.of(listing, options, false, registrationName(listing, options));
    }

    /**
     * ⚠️ On a cell that reached the market, an option that is registered there may not be unchecked: Coupang
     * cannot physically delete an approved option, so switching it off locally would only hide it from the next
     * payload while it keeps selling. Turning options <em>on</em> is always allowed, and so is undoing that
     * before the cell is re-pushed (the option is not on the market yet).
     *
     * <p>The lock reads {@link ProductListingOption#isMarketRegistered()}; 84's master-option lock is the
     * superset that adds {@code active} on top of it.</p>
     */
    @Override
    @Transactional
    public ListingOptionsResponse setActiveOptions(Long listingId, List<Long> activeOptionIds) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // At least one active option required (an empty product cannot be pushed).
        if (activeOptionIds == null || activeOptionIds.isEmpty()) {
            throw new IllegalArgumentException("활성 옵션 최소 1개");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Set<Long> ownIds = options.stream().map(ProductListingOption::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> requested = Set.copyOf(activeOptionIds);
        // Every requested id must belong to this listing (reject other-listing / master option ids mixed in).
        if (!ownIds.containsAll(requested)) {
            throw new IllegalArgumentException("리스팅 옵션 아님");
        }

        // A cell that reached the market: an option that physically exists there cannot be taken back off by
        // unchecking it (Coupang cannot delete an approved option), so refuse instead of pretending it worked.
        // ⚠️ Thrown before saveAll — no partially applied state, same place/exception type as the guards above.
        if (listing.getPlatformProductId() != null) {
            List<String> turningOff = options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getActive()) && !requested.contains(o.getId()))
                    .filter(ProductListingOption::isMarketRegistered)
                    .map(ProductListingOption::getOptionName)
                    .toList();
            if (!turningOff.isEmpty()) {
                throw new IllegalArgumentException(
                        "마켓에 등록된 옵션은 뺄 수 없습니다: " + String.join(", ", turningOff)
                        + " — 판매를 멈추려면 쿠팡 WING 에서 처리하세요.");
            }
        }

        // Immutable entity → toBuilder each option, then saveAll explicitly (no dirty-checking reliance so the
        // save is verifiable, and the two validation failures above never call saveAll).
        List<ProductListingOption> updated = options.stream()
                .map(option -> option.toBuilder()
                        .active(requested.contains(option.getId()))
                        .build())
                .toList();
        productListingOptionRepository.saveAll(updated);

        // Listing-level needsResync (single boolean, OR aggregate): if this listing is already pushed, the
        // active-set change alone does not reach the market → the front must re-register/update. No auto-push here.
        boolean needsResync = listing.getStatus() != null && listing.getStatus() != ListingStatus.DRAFT;
        return ListingOptionsResponse.of(listing, updated, needsResync, registrationName(listing, updated));
    }

    /**
     * The auto-generated registration name (등록상품명, 67) for this listing's current active options — carried in
     * the response so the front can patch the matrix cell in place after a toggle. master null → the listing's
     * display name (backfill transition window).
     */
    private String registrationName(ProductListing listing, List<ProductListingOption> options) {
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return listing.getName();
        }
        List<String> activeNames = options.stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .map(ProductListingOption::getOptionName)
                .toList();
        List<MasterProductOption> masterOptions =
                masterProductOptionRepository.findByMasterProductId(master.getId());
        // 69: suffix = channel(this cell's account) ?? master ?? seller ?? system (single cell = one account query).
        OptionCheckSuffix suffix = optionCheckSuffixResolver.resolve(listing);
        return registrationNameGenerator.generate(master, activeNames, masterOptions, suffix);
    }
}
