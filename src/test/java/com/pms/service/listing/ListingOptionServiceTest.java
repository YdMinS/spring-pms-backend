package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.RegistrationNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Per-channel option selection (FEATURE_2608_06 / 42): bulk active-set with min-1 + own-listing validation, and
 * the needsResync flag when the cell is already pushed. No auto-push.
 */
@ExtendWith(MockitoExtension.class)
class ListingOptionServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private com.pms.service.OptionCheckSuffixResolver optionCheckSuffixResolver;
    @InjectMocks private ListingOptionServiceImpl service;

    private static final Long LISTING_ID = 100L;

    private ProductListing listing(ListingStatus status) {
        return ProductListing.builder().id(LISTING_ID).platform("COUPANG").name("셀").status(status).build();
    }

    /** A cell that actually reached the market — 87's guard only applies to these (platformProductId != null). */
    private ProductListing pushedListing() {
        return ProductListing.builder().id(LISTING_ID).platform("COUPANG").name("셀")
                .status(ListingStatus.SELLING).platformProductId("P-1").build();
    }

    private ProductListingOption option(Long id, boolean active) {
        return option(id, active, null, OptionApprovalStatus.NOT_APPROVED);
    }

    /** Market fields spelled out: platformOptionId / approvalStatus are what isMarketRegistered() reads. */
    private ProductListingOption option(Long id, boolean active, String platformOptionId,
                                        OptionApprovalStatus approvalStatus) {
        return ProductListingOption.builder().id(id).optionName("opt" + id)
                .sellingPrice(new BigDecimal("6000")).active(active)
                .platformOptionId(platformOptionId).approvalStatus(approvalStatus).build();
    }

    // (a) happy: 3 options → activate opt1 + opt3 → saveAll flips opt2 to inactive; returns all 3.
    @Test
    void setActiveOptions_activatesSubset_savesAllAndReturnsFullSet() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true), option(3L, true)));

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L, 3L));

        ArgumentCaptor<List<ProductListingOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(captor.capture());
        Map<Long, Boolean> saved = captor.getValue().stream()
                .collect(Collectors.toMap(ProductListingOption::getId,
                        o -> Boolean.TRUE.equals(o.getActive()), (a, b) -> a));
        assertThat(saved).containsEntry(1L, true).containsEntry(2L, false).containsEntry(3L, true);

        Map<Long, ListingOptionsResponse.OptionItem> byId = response.getOptions().stream()
                .collect(Collectors.toMap(ListingOptionsResponse.OptionItem::getOptionId, Function.identity()));
        assertThat(byId).hasSize(3);
        assertThat(byId.get(2L).isActive()).isFalse();
        assertThat(response.isNeedsResync()).isFalse();   // DRAFT cell → no resync needed
    }

    // (b) empty active set → 400 & saveAll never called.
    @Test
    void setActiveOptions_emptySet_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));

        assertThatThrownBy(() -> service.setActiveOptions(LISTING_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (c) an id not belonging to this listing → 400 & saveAll never called.
    @Test
    void setActiveOptions_foreignOptionId_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));

        assertThatThrownBy(() -> service.setActiveOptions(LISTING_ID, List.of(1L, 999L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (d) market cell, turning an option ON: saved + needsResync=true; no auto-push. (Turning one off is (g)-(h).)
    @Test
    void setActiveOptions_pushedCell_savesAndFlagsNeedsResync() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pushedListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, false)));

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L, 2L));

        verify(productListingOptionRepository).saveAll(any());
        assertThat(response.isNeedsResync()).isTrue();
    }

    // (g) 87: market cell + an active option Coupang issued a vendorItemId for → cannot be unchecked.
    @Test
    void setActiveOptions_uncheckingOptionWithPlatformOptionId_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pushedListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true, "V-2", OptionApprovalStatus.NOT_APPROVED)));

        assertThatThrownBy(() -> service.setActiveOptions(LISTING_ID, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opt2");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (h) 87: the OR's second term — approved at some point, no vendorItemId yet → still locked.
    @Test
    void setActiveOptions_uncheckingApprovedOption_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pushedListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true, null, OptionApprovalStatus.APPROVED)));

        assertThatThrownBy(() -> service.setActiveOptions(LISTING_ID, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (i) 87: an active option that never reached the market may still be switched back off (undo before re-push).
    //     Proves the judgement drops `active` — 84's three-way OR would have locked this one.
    @Test
    void setActiveOptions_uncheckingNotYetPushedOption_succeeds() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pushedListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));

        service.setActiveOptions(LISTING_ID, List.of(1L));

        ArgumentCaptor<List<ProductListingOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(captor.capture());
        Map<Long, Boolean> saved = captor.getValue().stream()
                .collect(Collectors.toMap(ProductListingOption::getId,
                        o -> Boolean.TRUE.equals(o.getActive()), (a, b) -> a));
        assertThat(saved).containsEntry(2L, false);
    }

    // (j) 87: turning a market-registered but inactive option back on is always allowed.
    @Test
    void setActiveOptions_reactivatingMarketRegisteredOption_succeeds() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pushedListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, false, "V-2", OptionApprovalStatus.APPROVED)));

        service.setActiveOptions(LISTING_ID, List.of(1L, 2L));

        verify(productListingOptionRepository).saveAll(any());
    }

    // (k) DRAFT cell: nothing is on the market yet → unchecking stays free (no regression from the guard).
    @Test
    void setActiveOptions_draftCell_uncheckingMarketFieldsOption_succeeds() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true, "V-2", OptionApprovalStatus.APPROVED)));

        service.setActiveOptions(LISTING_ID, List.of(1L));

        verify(productListingOptionRepository).saveAll(any());
    }

    // (e) 67: the response carries the auto-generated registration name for the current active set — reducing
    // 2 → 1 active option yields the single-option name (front patches the matrix cell in place, no extra call).
    @Test
    void setActiveOptions_carriesPerChannelRegistrationNameForActiveSet() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform("COUPANG").name("셀")
                .status(ListingStatus.DRAFT).masterProduct(master).build();
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));
        List<MasterProductOption> masterOptions = List.of(
                MasterProductOption.builder().id(5L).name("opt1").build(),
                MasterProductOption.builder().id(6L).name("opt2").build());
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(masterOptions);
        // Only opt1 stays active → generator receives ["opt1"] → single-option name.
        given(registrationNameGenerator.generate(eq(master), eq(List.of("opt1")), any(), any()))
                .willReturn("노브랜드 생수 x 6");

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L));

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수 x 6");
    }

    // (f) 69: the channel-resolved suffix (via resolve(cell)) is threaded into the generator for the response name.
    @Test
    void setActiveOptions_registrationNameReflectsChannelSuffixOverride() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform("COUPANG").name("셀")
                .status(ListingStatus.DRAFT).masterProduct(master).build();
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductOption.builder().id(5L).name("opt1").build(),
                MasterProductOption.builder().id(6L).name("opt2").build()));
        OptionCheckSuffix off = new OptionCheckSuffix(false, "옵션확인");
        given(optionCheckSuffixResolver.resolve(listing)).willReturn(off);
        // Generator is called with the resolved (OFF) suffix → the "옵션확인"-less name for this channel.
        given(registrationNameGenerator.generate(eq(master), eq(List.of("opt1", "opt2")), any(), eq(off)))
                .willReturn("노브랜드 생수, 다우니 섬유유연제");

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L, 2L));

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수, 다우니 섬유유연제");
    }
}
