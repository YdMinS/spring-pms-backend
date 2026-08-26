package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
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
    @InjectMocks private ListingOptionServiceImpl service;

    private static final Long LISTING_ID = 100L;

    private ProductListing listing(ListingStatus status) {
        return ProductListing.builder().id(LISTING_ID).platform("COUPANG").name("셀").status(status).build();
    }

    private ProductListingOption option(Long id, boolean active) {
        return ProductListingOption.builder().id(id).optionName("opt" + id)
                .sellingPrice(new BigDecimal("6000")).active(active).build();
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

    // (d) already-pushed cell (SELLING): saved + needsResync=true; no auto-push.
    @Test
    void setActiveOptions_pushedCell_savesAndFlagsNeedsResync() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.SELLING)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L));

        verify(productListingOptionRepository).saveAll(any());
        assertThat(response.isNeedsResync()).isTrue();
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
        given(registrationNameGenerator.generate(eq(master), eq(List.of("opt1")), any()))
                .willReturn("노브랜드 생수 x 6");

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L));

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수 x 6");
    }
}
