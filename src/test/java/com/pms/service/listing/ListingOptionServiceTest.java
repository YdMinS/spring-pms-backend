package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.request.SetOptionNamesRequest;
import com.pms.dto.request.SetOptionPricesRequest.OptionPrice;
import com.pms.dto.request.SetOptionStocksRequest.OptionStock;
import com.pms.dto.response.ChannelPriceUpdateResponse;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    // 2609_19: manual pricing dependencies — declared here so @InjectMocks fills them too.
    @Mock private com.pms.service.PriceCalculator priceCalculator;
    @Mock private com.pms.service.ListingAssetService listingAssetService;
    @Mock private ListingChannelResolver resolver;
    @Mock private com.pms.repository.MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannel adapter;
    // 2609_28/D23: real recorder over a mocked repository — the skip rules run end-to-end from the hook.
    @Mock private com.pms.repository.PriceChangeLogRepository priceChangeLogRepository;
    @InjectMocks private ListingOptionServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void injectPriceHistoryRecorder() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "priceHistoryRecorder",
                new com.pms.service.price.PriceHistoryRecorder(priceChangeLogRepository));
    }

    private static final Long LISTING_ID = 100L;

    private ProductListing listing(ListingStatus status) {
        return ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀").status(status).build();
    }

    /** A cell that actually reached the market — 87's guard only applies to these (platformProductId != null). */
    private ProductListing pushedListing() {
        return ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
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
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                .status(ListingStatus.DRAFT).masterProduct(master).build();
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(1L, true), option(2L, true)));
        List<MasterProductOption> masterOptions = List.of(
                MasterProductOption.builder().id(5L).name("opt1").build(),
                MasterProductOption.builder().id(6L).name("opt2").build());
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(masterOptions);
        // 2609_22/D7: the generator receives the active option ROWS (only opt1 stays active).
        given(registrationNameGenerator.generate(eq(master), anyList(), any()))
                .willReturn("노브랜드 생수 x 6");

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L));

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수 x 6");
    }

    // (f) 69: the channel-resolved suffix (via resolve(cell)) is threaded into the generator for the response name.
    @Test
    void setActiveOptions_registrationNameReflectsChannelSuffixOverride() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
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
        given(registrationNameGenerator.generate(eq(master), anyList(), eq(off)))
                .willReturn("노브랜드 생수, 다우니 섬유유연제");

        ListingOptionsResponse response = service.setActiveOptions(LISTING_ID, List.of(1L, 2L));

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수, 다우니 섬유유연제");
    }

    // ---------------------------------------------------------------- 102: per-channel option stock

    /**
     * A cell with a master + that master's per-option stock ceilings ({@code null} = master leaves it unset).
     * ⚠️ Stubs two mocks, so call it before {@code given(...)} — Mockito rejects a stub built inside another.
     */
    private ProductListing listingWithMasterStocks(String name1, Integer stock1, String name2, Integer stock2) {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        List<MasterProductOption> masterOptions = new java.util.ArrayList<>();
        // 2609_22/D1: master options carry ids because the FK — not the name — is the matching axis
        // (opt1 ↔ 5L, opt2 ↔ 6L; see optionWithStock).
        masterOptions.add(MasterProductOption.builder().id(5L).name(name1).stockQuantity(stock1).build());
        if (name2 != null) {
            masterOptions.add(MasterProductOption.builder().id(6L).name(name2).stockQuantity(stock2).build());
        }
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(masterOptions);
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                .status(ListingStatus.DRAFT).masterProduct(master).build();
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
        return listing;
    }

    /** Linked to master option {@code 4 + id} (opt1 → 5L, opt2 → 6L) — the 2609_22/D1 matching axis. */
    private ProductListingOption optionWithStock(Long id, Integer stockQuantity) {
        return ProductListingOption.builder().id(id).optionName("opt" + id)
                .masterProductOption(MasterProductOption.builder().id(4L + id).build())
                .sellingPrice(new BigDecimal("6000")).active(true)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .stockQuantity(stockQuantity).build();
    }

    // (g) Partial update: sending one of two options leaves the other's value untouched.
    @Test
    void setOptionStocks_updatesOnlyTheListedOptions() {
        listingWithMasterStocks("opt1", 50, "opt2", 50);
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(optionWithStock(1L, 5), optionWithStock(2L, 7)));

        ListingOptionsResponse response =
                service.setOptionStocks(LISTING_ID, List.of(new OptionStock(1L, 30)));

        ArgumentCaptor<List<ProductListingOption>> saved = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getStockQuantity()).isEqualTo(30);
        Map<Long, ListingOptionsResponse.OptionItem> byId = response.getOptions().stream()
                .collect(Collectors.toMap(ListingOptionsResponse.OptionItem::getOptionId, Function.identity()));
        assertThat(byId.get(1L).getStockQuantity()).isEqualTo(30);
        assertThat(byId.get(2L).getStockQuantity()).isEqualTo(7);   // untouched
        assertThat(byId.get(1L).getMaxStock()).isEqualTo(50);       // ceiling = master stock
    }

    // (h) null clears the override → the option inherits the master value again.
    @Test
    void setOptionStocks_nullValue_clearsTheOverride() {
        listingWithMasterStocks("opt1", 50, null, null);
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(optionWithStock(1L, 30)));

        ListingOptionsResponse response =
                service.setOptionStocks(LISTING_ID, List.of(new OptionStock(1L, null)));

        ArgumentCaptor<List<ProductListingOption>> saved = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getStockQuantity()).isNull();
        assertThat(response.getOptions().get(0).getStockQuantity()).isNull();
        assertThat(response.getOptions().get(0).getMaxStock()).isEqualTo(50);   // effective value while inherited
    }

    // (i) D5: above the master's stock → 400, and nothing is saved (validation precedes saveAll).
    @Test
    void setOptionStocks_aboveMasterStock_throwsAndDoesNotSave() {
        listingWithMasterStocks("opt1", 50, null, null);
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(optionWithStock(1L, null)));

        assertThatThrownBy(() -> service.setOptionStocks(LISTING_ID, List.of(new OptionStock(1L, 51))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마스터 재고(50)");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (j) Master stock unset → the ceiling is the 9999 fallback: 9999 passes, 10000 does not.
    @Test
    void setOptionStocks_masterStockUnset_ceilingIs9999() {
        listingWithMasterStocks("opt1", null, null, null);
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(optionWithStock(1L, null)));

        ListingOptionsResponse response =
                service.setOptionStocks(LISTING_ID, List.of(new OptionStock(1L, 9999)));
        assertThat(response.getOptions().get(0).getStockQuantity()).isEqualTo(9999);

        assertThatThrownBy(() -> service.setOptionStocks(LISTING_ID, List.of(new OptionStock(1L, 10000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9999");
    }

    // (k) An id from another listing → 400, nothing saved (same rule as setActiveOptions).
    @Test
    void setOptionStocks_foreignOptionId_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID))
                .willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(optionWithStock(1L, null)));

        assertThatThrownBy(() -> service.setOptionStocks(LISTING_ID, List.of(new OptionStock(999L, 10))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리스팅 옵션 아님");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (l) An empty list is a mistake, not a silent no-op.
    @Test
    void setOptionStocks_emptyList_throwsAndDoesNotSave() {
        given(productListingRepository.findScopedById(LISTING_ID))
                .willReturn(Optional.of(listing(ListingStatus.DRAFT)));

        assertThatThrownBy(() -> service.setOptionStocks(LISTING_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("변경할 옵션이 없습니다");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // (m) 2609_22/D2·D6: a channel-only option has no master option behind it → ceiling stays 9999
    //     (the pre-2609_22 behaviour for an unmatched option, unchanged on purpose).
    @Test
    void setOptionStocks_channelOnlyOption_ceilingIs9999() {
        listingWithMasterStocks("opt1", 50, null, null);
        ProductListingOption channelOnly = ProductListingOption.builder().id(9L).optionName("채널전용")
                .sellingPrice(new BigDecimal("6000")).active(true)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED).build();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(channelOnly));

        ListingOptionsResponse response =
                service.setOptionStocks(LISTING_ID, List.of(new OptionStock(9L, 9999)));

        assertThat(response.getOptions().get(0).getMaxStock()).isEqualTo(9999);
        assertThat(response.getOptions().get(0).isChannelOnly()).isTrue();
    }

    // ---------------------------------------------------------------- 2609_22: per-channel option names

    /** {@code linked} = the master option this cell option points at (null = channel-only, D2). */
    private ProductListingOption namedOption(Long id, String name, MasterProductOption linked,
                                             GeneratedContentSource nameSource) {
        return ProductListingOption.builder().id(id).optionName(name)
                .masterProductOption(linked).optionNameSource(nameSource)
                .sellingPrice(new BigDecimal("6000")).active(true)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED).build();
    }

    /** A cell whose master owns one option (id 5, "2세트"). */
    private void givenNamingListing() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(
                List.of(MasterProductOption.builder().id(5L).name("2세트").build()));
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(
                ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                        .status(ListingStatus.DRAFT).masterProduct(master).build()));
    }

    // 6. A name the channel typed is stored and flagged MANUAL_OVERRIDE (so a master rename skips it, D4).
    @Test
    void setOptionNames_savesNameAsManualOverride() {
        givenNamingListing();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                namedOption(1L, "2세트", MasterProductOption.builder().id(5L).build(),
                        GeneratedContentSource.AUTO)));

        service.setOptionNames(LISTING_ID, List.of(
                new SetOptionNamesRequest.Item(1L, "생수 6개입")));

        List<ProductListingOption> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getOptionName()).isEqualTo("생수 6개입");
        assertThat(saved.get(0).getOptionNameSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
    }

    // 7. Blank = back to the linked master option's name + AUTO.
    @Test
    void setOptionNames_blankValue_revertsToMasterNameAndAuto() {
        givenNamingListing();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                namedOption(1L, "채널이 붙인 이름", MasterProductOption.builder().id(5L).build(),
                        GeneratedContentSource.MANUAL_OVERRIDE)));

        service.setOptionNames(LISTING_ID, List.of(new SetOptionNamesRequest.Item(1L, "  ")));

        List<ProductListingOption> saved = captureSaved();
        assertThat(saved.get(0).getOptionName()).isEqualTo("2세트");
        assertThat(saved.get(0).getOptionNameSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    // 8. A channel-only option (D2) has no master name to go back to → 400, nothing saved.
    @Test
    void setOptionNames_blankOnChannelOnlyOption_throws() {
        givenNamingListing();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                namedOption(1L, "채널전용", null, GeneratedContentSource.MANUAL_OVERRIDE)));

        assertThatThrownBy(() -> service.setOptionNames(LISTING_ID,
                List.of(new SetOptionNamesRequest.Item(1L, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("채널 전용 옵션");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // 9. Two options of one cell may not share a name (Coupang itemName) → 400 before any save.
    @Test
    void setOptionNames_duplicateNameInCell_throwsAndDoesNotSave() {
        givenNamingListing();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                namedOption(1L, "2세트", MasterProductOption.builder().id(5L).build(),
                        GeneratedContentSource.AUTO),
                namedOption(2L, "3세트", null, GeneratedContentSource.MANUAL_OVERRIDE)));

        assertThatThrownBy(() -> service.setOptionNames(LISTING_ID,
                List.of(new SetOptionNamesRequest.Item(1L, "3세트"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 이름의 옵션");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // ---------------------------------------------------------------- 2609_19: manual channel price

    /** A cell with a seller, so the market path can resolve its marketplace account. */
    private ProductListing pricingListing() {
        return ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                .status(ListingStatus.DRAFT)
                .seller(com.pms.domain.Seller.builder().id(7L).sellerName("행복상회").build())
                .build();
    }

    /** {@code platformOptionId} = the Coupang vendorItemId; null means "not on the market yet". */
    private ProductListingOption pricedOption(Long id, String platformOptionId) {
        return ProductListingOption.builder().id(id).optionName("opt" + id)
                .sellingPrice(new BigDecimal("6000.00")).originalPrice(new BigDecimal("7500.00"))
                .active(true).approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .platformOptionId(platformOptionId).build();
    }

    private com.pms.domain.MarketplaceAccount activeAccount() {
        return com.pms.domain.MarketplaceAccount.builder().id(3L).platform(Platform.COUPANG).isActive(true).build();
    }

    /** Stub the (seller, platform) account lookup used by the market path. */
    private void givenActiveAccount() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(activeAccount()));
    }

    @SuppressWarnings("unchecked")
    private List<ProductListingOption> captureSaved() {
        ArgumentCaptor<List<ProductListingOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** A live cell (SELLING) — a DRAFT one is skipped by the price-history recorder on purpose. */
    private ProductListing livePricingListing() {
        return ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                .status(ListingStatus.SELLING)
                .seller(com.pms.domain.Seller.builder().id(7L).sellerName("행복상회").build())
                .build();
    }

    // 0. 가격 변경 이력 훅 ④ (PLAN 2609_28 D23): 수동 지정은 seam 밖이라 여기서만 남길 수 있다.
    @Test
    @SuppressWarnings("unchecked")
    void testManualPriceRecordsManual() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(livePricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));

        service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        ArgumentCaptor<List<com.pms.domain.PriceChangeLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceChangeLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        com.pms.domain.PriceChangeLog row = captor.getValue().get(0);
        assertThat(row.getTargetType()).isEqualTo(com.pms.domain.PriceTargetType.LISTING_SELLING);
        assertThat(row.getReason()).isEqualTo(com.pms.domain.PriceChangeReason.MANUAL);
        assertThat(row.getOldPrice()).isEqualByComparingTo("6000");
        assertThat(row.getNewPrice()).isEqualByComparingTo("15000");
        assertThat(row.getListingOption().getId()).isEqualTo(1L);
    }

    // 1. A hand-set price is stored as-is and flagged MANUAL_OVERRIDE (so a regeneration will skip it).
    @Test
    void setOptionPricesSavesManualPrice() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));

        service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        ProductListingOption saved = captureSaved().get(0);
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("15000");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
    }

    // 2. null = back to the calculated price, taken from the calculate-only seam (never from PriceCalculator here).
    @Test
    void setOptionPricesRecalculatesWhenNull() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));
        given(listingAssetService.quoteOptionPrice(any(), any()))
                .willReturn(new com.pms.service.PriceCalculator.PriceResult(
                        new BigDecimal("9000.00"), new BigDecimal("11250.00")));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));

        service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, null)));

        ProductListingOption saved = captureSaved().get(0);
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("9000");
        assertThat(saved.getOriginalPrice()).isEqualByComparingTo("11250");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
        verify(priceCalculator, never()).displayOriginalPrice(any(), any());
    }

    // 3. D7: the strike-through price is refreshed with the manual price, or the market shows sale > original.
    @Test
    void setOptionPricesUpdatesOriginalPrice() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));
        given(priceCalculator.displayOriginalPrice(any(), eq(new BigDecimal("15000.00"))))
                .willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));

        service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        assertThat(captureSaved().get(0).getOriginalPrice()).isEqualByComparingTo("18750");
    }

    // 4. An option that exists on the market is repriced there in this same request (no re-approval).
    @Test
    void setOptionPricesPushesToMarket() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, "V-1")));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));
        givenActiveAccount();

        ChannelPriceUpdateResponse response =
                service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        verify(adapter).updateOptionPrice(any(), eq(new BigDecimal("15000.00")), any());
        assertThat(response.pushed()).isEqualTo(1);
        assertThat(response.skipped()).isEmpty();
        assertThat(response.failed()).isEmpty();
    }

    // 5. D5: no vendorItemId (unapproved / DRAFT) → saved locally, reported as skipped, adapter untouched.
    @Test
    void setOptionPricesSkipsWhenNoMarketId() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));

        ChannelPriceUpdateResponse response =
                service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        verify(adapter, never()).updateOptionPrice(any(), any(), any());
        verify(marketplaceAccountRepository, never()).findBySeller_IdAndPlatform(any(), any());
        assertThat(response.skipped()).containsExactly("opt1");
        assertThat(response.pushed()).isZero();
        assertThat(captureSaved()).hasSize(1);
    }

    // 6. D6: what the market refused is NOT saved — the DB must not claim a price the market never took.
    @Test
    void setOptionPricesDoesNotSaveWhenMarketFails() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, "V-1")));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("18750.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));
        givenActiveAccount();
        org.mockito.BDDMockito.willThrow(new IllegalStateException("쿠팡 가격변경 실패: 판매중이 아닌 상품"))
                .given(adapter).updateOptionPrice(any(), any(), any());

        ChannelPriceUpdateResponse response =
                service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("15000"))));

        assertThat(captureSaved()).isEmpty();
        assertThat(response.pushed()).isZero();
        assertThat(response.failed()).hasSize(1);
        assertThat(response.failed().get(0).optionName()).isEqualTo("opt1");
        assertThat(response.failed().get(0).message()).contains("판매중이 아닌 상품");
    }

    // 7. An id from another listing → 400, nothing saved (same rule/message as the stock write).
    @Test
    void setOptionPricesRejectsForeignOption() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, null)));

        assertThatThrownBy(() -> service.setOptionPrices(LISTING_ID,
                List.of(new OptionPrice(999L, new BigDecimal("15000")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리스팅 옵션 아님");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // 8. An empty list is a mistake, not a silent no-op.
    @Test
    void setOptionPricesRejectsEmptyList() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing(ListingStatus.DRAFT)));

        assertThatThrownBy(() -> service.setOptionPrices(LISTING_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("변경할 옵션이 없습니다");
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // 9. D3 × D4: going back to AUTO also reaches the market — the restored price must be the live one.
    @Test
    void setOptionPricesPushesRestoredAutoPrice() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, "V-1")));
        given(listingAssetService.quoteOptionPrice(any(), any()))
                .willReturn(new com.pms.service.PriceCalculator.PriceResult(
                        new BigDecimal("9000.00"), new BigDecimal("11250.00")));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));
        givenActiveAccount();

        ChannelPriceUpdateResponse response =
                service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, null)));

        verify(adapter).updateOptionPrice(any(), eq(new BigDecimal("9000.00")), any());
        assertThat(response.pushed()).isEqualTo(1);
        assertThat(captureSaved().get(0).getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    // 10. D13: whole won, and the value sent must equal the value stored (no decimal left in the price path).
    @Test
    void setOptionPricesNormalizesToWon() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, "V-1")));
        given(priceCalculator.displayOriginalPrice(any(), any())).willReturn(new BigDecimal("16250.00"));
        given(resolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(adapter));
        givenActiveAccount();

        service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, new BigDecimal("12999.99"))));

        ArgumentCaptor<BigDecimal> sent = ArgumentCaptor.forClass(BigDecimal.class);
        verify(adapter).updateOptionPrice(any(), sent.capture(), any());
        assertThat(sent.getValue()).isEqualByComparingTo("13000");
        assertThat(captureSaved().get(0).getSellingPrice()).isEqualByComparingTo("13000");
    }

    // 11. D16: an option going back to AUTO that cannot be priced fails the WHOLE request — no partial save,
    //     and nothing is sent to the market either (we would not know what to put back).
    @Test
    void setOptionPricesFailsWhenAutoRestoreCannotPrice() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(pricingListing()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(pricedOption(1L, "V-1")));
        given(listingAssetService.quoteOptionPrice(any(), any()))
                .willThrow(new IllegalArgumentException("마진 프리셋 없음"));

        assertThatThrownBy(() -> service.setOptionPrices(LISTING_ID, List.of(new OptionPrice(1L, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마진 프리셋 없음");
        verify(productListingOptionRepository, never()).saveAll(any());
        verify(adapter, never()).updateOptionPrice(any(), any(), any());
    }
}
