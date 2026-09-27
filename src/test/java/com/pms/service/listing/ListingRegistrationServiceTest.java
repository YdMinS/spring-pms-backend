package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.dto.response.MarketOptionResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Orchestration (FEATURE_2608_06 / 3c): register state promotion (no approval wait), fetch-status option sync
 * on SELLING, and the sync-approvals sweep isolating a per-listing failure.
 */
@ExtendWith(MockitoExtension.class)
class ListingRegistrationServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel adapter;
    @Mock private TagMergeService tagMergeService;
    @InjectMocks private ListingRegistrationServiceImpl service;

    private static final Long CELL_ID = 100L;
    private static final Long SELLER_ID = 7L;

    private ProductListing cell(ListingStatus status, String platformProductId) {
        return ProductListing.builder().id(CELL_ID).platform(Platform.COUPANG).name("셀")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(status).platformProductId(platformProductId).build();
    }

    private MarketplaceAccount account() {
        return MarketplaceAccountFixture.coupangStubBuilder("V1", null).isActive(true).build();
    }

    private ProductListingOption option() {
        return ProductListingOption.builder().id(50L).optionName("기본")
                .sellingPrice(new BigDecimal("6000")).build();   // @Builder.Default approvalStatus = NOT_APPROVED
    }

    private void stubAccountAndAdapter() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq(Platform.COUPANG)))
                .willReturn(Optional.of(account()));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
    }

    // (a) register happy: DRAFT + gen → SUBMITTED + platformProductId; options untouched.
    @Test
    void register_draftWithGen_promotesToSubmitted_optionsUnchanged() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        stubAccountAndAdapter();
        // 42 register guard reads the options to confirm at least one is active (option() defaults active=true).
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(adapter.register(any(), any(), any())).willReturn("SP-999");

        ListingRegisterResponse response = service.register(CELL_ID);

        assertThat(response.getStatus()).isEqualTo("SUBMITTED");
        assertThat(response.getPlatformProductId()).isEqualTo("SP-999");

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ListingStatus.SUBMITTED);
        assertThat(captor.getValue().getPlatformProductId()).isEqualTo("SP-999");
        verify(productListingOptionRepository, never()).save(any());   // approval untouched at register
    }

    // (b) register guards: already registered → 400 & adapter untouched; gen missing → 400.
    @Test
    void register_notDraft_throwsAndAdapterNotCalled() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SELLING, "SP-1")));

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(IllegalArgumentException.class);
        verify(adapter, never()).register(any(), any(), any());
    }

    @Test
    void register_noGeneratedData_throws() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    // 42: register with no active option → 400 & adapter never called (payload would be empty).
    @Test
    void register_noActiveOptions_throwsAndAdapterNotCalled() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        stubAccountAndAdapter();
        ProductListingOption inactive = option().toBuilder().active(false).build();
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(inactive));

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(IllegalArgumentException.class);
        verify(adapter, never()).register(any(), any(), any());
    }

    // account resolution: none → 404 (ResourceNotFoundException).
    @Test
    void register_accountMissing_throwsNotFound() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq(Platform.COUPANG)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // 63: the orchestration delegates the registration policy to the adapter — validateRegistrable runs before
    // register (channel-vocabulary-free). The Coupang required-attribute / AB-skip details live in the adapter.
    @Test
    void register_delegatesValidateRegistrableBeforeRegister() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        stubAccountAndAdapter();
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(adapter.register(any(), any(), any())).willReturn("SP-1");

        service.register(CELL_ID);

        InOrder inOrder = inOrder(adapter);
        inOrder.verify(adapter).validateRegistrable(any(), any(), any());
        inOrder.verify(adapter).register(any(), any(), any());
    }

    // 63: validateRegistrable throwing (e.g. missing required attribute) blocks register (never reached).
    @Test
    void register_validateRegistrableThrows_registerNotCalled() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        stubAccountAndAdapter();
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        willThrow(new IllegalArgumentException("필수 카테고리 속성 누락"))
                .given(adapter).validateRegistrable(any(), any(), any());

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(IllegalArgumentException.class);
        verify(adapter, never()).register(any(), any(), any());
    }

    // ---- 108/D4: updateRequest — single-cell forced re-review ----

    // 1: pushes via the adapter and marks the cell SUBMITTED + not dirty.
    @Test
    void updateRequest_pushesAndMarksSubmitted() {
        ProductListing cell = cell(ListingStatus.REJECTED, "SP-1").toBuilder().needsMarketSync(true).build();
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        stubAccountAndAdapter();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        ListingRegisterResponse response = service.updateRequest(CELL_ID);

        assertThat(response.getStatus()).isEqualTo("SUBMITTED");
        assertThat(response.getPlatformProductId()).isEqualTo("SP-1");
        verify(adapter).update(any(), any(), any());

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ListingStatus.SUBMITTED);
        assertThat(captor.getValue().isNeedsMarketSync()).isFalse();
    }

    // 2: a DRAFT cell was never pushed → 400 and the adapter is never called.
    @Test
    void updateRequest_rejectsDraftCell() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));

        assertThatThrownBy(() -> service.updateRequest(CELL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("미등록");
        verify(adapter, never()).update(any(), any(), any());
    }

    // 3: deactivated options that were APPROVED are reverted (they are dropped from the payload); active
    // APPROVED options keep their approval.
    @Test
    void updateRequest_revertsApprovedInactiveOption() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SELLING, "SP-1")));
        stubAccountAndAdapter();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        ProductListingOption activeApproved = option().toBuilder()
                .id(50L).approvalStatus(OptionApprovalStatus.APPROVED).active(true).build();
        ProductListingOption inactiveApproved = option().toBuilder()
                .id(51L).approvalStatus(OptionApprovalStatus.APPROVED).active(false).build();
        given(productListingOptionRepository.findByProductListingId(CELL_ID))
                .willReturn(List.of(activeApproved, inactiveApproved));

        service.updateRequest(CELL_ID);

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(51L);
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(OptionApprovalStatus.NOT_APPROVED);
    }

    // 4: no status transition guard — a SELLING cell re-enters review too.
    @Test
    void updateRequest_allowsSellingCell() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SELLING, "SP-1")));
        stubAccountAndAdapter();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        assertThat(service.updateRequest(CELL_ID).getStatus()).isEqualTo("SUBMITTED");
    }

    // (c) fetchStatus SELLING: matched option → market ids + APPROVED saved; status saved.
    @Test
    void fetchStatus_selling_syncsMatchedOptionApproved() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any())).willReturn(new FetchResult(
                ListingStatus.SELLING, List.of(new FetchResult.OptionId("기본", "111", "222"))));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        service.fetchStatus(CELL_ID);

        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getPlatformOptionId()).isEqualTo("111");
        assertThat(optionCaptor.getValue().getSellerProductItemId()).isEqualTo("222");
        assertThat(optionCaptor.getValue().getApprovalStatus()).isEqualTo(OptionApprovalStatus.APPROVED);

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getStatus()).isEqualTo(ListingStatus.SELLING);
    }

    // (d) fetchStatus REJECTED: status saved, options untouched.
    @Test
    void fetchStatus_rejected_savesStatusOptionsUntouched() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any())).willReturn(new FetchResult(ListingStatus.REJECTED, List.of()));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        service.fetchStatus(CELL_ID);

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getStatus()).isEqualTo(ListingStatus.REJECTED);
        verify(productListingOptionRepository, never()).save(any());   // NOT_APPROVED kept
    }

    // (e) syncApprovals: 2 pending → each fetchStatus; 1 throws → others proceed, failed counted.
    @Test
    void syncApprovals_isolatesPerListingFailure() {
        ProductListing ok = ProductListing.builder().id(1L).platform(Platform.COUPANG).name("ok")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-1").build();
        ProductListing boom = ProductListing.builder().id(2L).platform(Platform.COUPANG).name("boom")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-2").build();
        given(productListingRepository.findPendingApproval()).willReturn(List.of(ok, boom));
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(ok));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(boom));
        lenient().when(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), any()))
                .thenReturn(Optional.of(account()));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
        given(adapter.fetchStatus(eq(ok), any())).willReturn(new FetchResult(ListingStatus.SELLING, List.of()));
        given(adapter.fetchStatus(eq(boom), any())).willThrow(new RuntimeException("coupang 500"));
        lenient().when(productListingOptionRepository.findByProductListingId(1L)).thenReturn(List.of());

        ListingSyncResponse response = service.syncApprovals();

        assertThat(response.getSwept()).isEqualTo(2);
        assertThat(response.getPromotedToSelling()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(1);
    }


    // (g) 2609_74/D9: a rejected refresh carries the review reason read on demand.
    @Test
    void fetchStatus_rejected_returnsReviewNote() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any()))
                .willReturn(new FetchResult(ListingStatus.REJECTED, List.of(), "승인반려"));
        given(adapter.fetchReviewNote(any(), any(), eq("승인반려"))).willReturn(ReviewNote.found("고시 누락"));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        ListingStatusResponse response = service.fetchStatus(CELL_ID);

        assertThat(response.getReviewNote()).isEqualTo("고시 누락");
        assertThat(response.getReviewNoteState()).isEqualTo("FOUND");
        assertThat(response.getStatus()).isEqualTo("REJECTED");
    }

    // (h) 2609_74/D19: a failed reason lookup never fails the refresh — the status is still saved.
    @Test
    void fetchStatus_reviewNoteLookupFails_stillReturnsStatus() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any()))
                .willReturn(new FetchResult(ListingStatus.REJECTED, List.of(), "승인반려"));
        given(adapter.fetchReviewNote(any(), any(), any())).willThrow(new IllegalStateException("boom"));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        ListingStatusResponse response = service.fetchStatus(CELL_ID);

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(response.getReviewNoteState()).isEqualTo("FAILED");
        assertThat(response.getReviewNote()).isNull();
        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getStatus()).isEqualTo(ListingStatus.REJECTED);
    }

    // (i) 2609_74: a status the adapter does not look up leaves both fields null.
    @Test
    void fetchStatus_noReviewNote_leavesBothFieldsNull() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any())).willReturn(new FetchResult(
                ListingStatus.SELLING, List.of(new FetchResult.OptionId("기본", "111", "222"))));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));

        ListingStatusResponse response = service.fetchStatus(CELL_ID);

        assertThat(response.getReviewNote()).isNull();
        assertThat(response.getReviewNoteState()).isNull();
    }

    // (j) 2609_74: the sweep refreshes only — it never looks a review reason up.
    @Test
    void syncApprovals_neverLooksUpReviewNote() {
        ProductListing ok = ProductListing.builder().id(1L).platform(Platform.COUPANG).name("ok")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-1").build();
        ProductListing boom = ProductListing.builder().id(2L).platform(Platform.COUPANG).name("boom")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-2").build();
        given(productListingRepository.findPendingApproval()).willReturn(List.of(ok, boom));
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(ok));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(boom));
        lenient().when(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), any()))
                .thenReturn(Optional.of(account()));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
        given(adapter.fetchStatus(eq(ok), any())).willReturn(new FetchResult(ListingStatus.SELLING, List.of()));
        given(adapter.fetchStatus(eq(boom), any())).willThrow(new RuntimeException("coupang 500"));
        lenient().when(productListingOptionRepository.findByProductListingId(1L)).thenReturn(List.of());

        service.syncApprovals();

        verify(adapter, never()).fetchReviewNote(any(), any(), any());
    }

    // (f) 2609_39/D19 ③: 식별자를 <b>처음</b> 받는 옵션만 market_price 를 얻는다. 이미 식별자가 있던 옵션은
    //     재동기화일 뿐 가격을 보낸 적이 없으므로 그대로 둔다(089 이후 등록분이 「아직 안 밀림」으로 쌓이는 것을 막는다).
    @Test
    void testApprovalSyncRecordsMarketPriceOnFirstIdentifier() {
        ProductListingOption fresh = option();                                     // platformOptionId == null
        ProductListingOption resynced = ProductListingOption.builder().id(51L).optionName("추가")
                .sellingPrice(new BigDecimal("9000")).platformOptionId("333").build();
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SUBMITTED, "SP-1")));
        stubAccountAndAdapter();
        given(adapter.fetchStatus(any(), any())).willReturn(new FetchResult(ListingStatus.SELLING, List.of(
                new FetchResult.OptionId("기본", "111", "222"),
                new FetchResult.OptionId("추가", "333", "444"))));
        given(productListingOptionRepository.findByProductListingId(CELL_ID))
                .willReturn(List.of(fresh, resynced));

        service.fetchStatus(CELL_ID);

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(captor.capture());
        ProductListingOption savedFresh = captor.getAllValues().stream()
                .filter(o -> o.getId().equals(50L)).findFirst().orElseThrow();
        ProductListingOption savedResynced = captor.getAllValues().stream()
                .filter(o -> o.getId().equals(51L)).findFirst().orElseThrow();
        assertThat(savedFresh.getMarketPrice()).isEqualByComparingTo("6000");   // 등록 payload 로 나간 가격
        assertThat(savedFresh.getMarketPriceAt()).isNotNull();
        assertThat(savedResynced.getMarketPrice()).isNull();
        assertThat(savedResynced.getMarketPriceAt()).isNull();
    }

    // ---- 2609_74/D13: market option list + manual link ----

    private ImportedProduct marketProduct() {
        return new ImportedProduct("상품", "C-1", ListingStatus.SELLING, List.of(), List.of(
                new ImportedProduct.Option("59g 6개", "V-1", "S-1", new BigDecimal("12900"), null, null),
                new ImportedProduct.Option("59g 12개", "V-2", "S-2", new BigDecimal("23900"), null, null)));
    }

    private void givenMarketCell(List<ProductListingOption> options) {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SELLING, "SP-1")));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(options);
        stubAccountAndAdapter();
        given(adapter.fetchProduct(eq("SP-1"), any())).willReturn(marketProduct());
    }

    private void givenCellAndOptions(List<ProductListingOption> options) {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.SELLING, "SP-1")));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(options);
    }

    @Test
    void linkMarketOption_optionWithoutId_storesIdAndApproval() {
        givenMarketCell(List.of(option()));
        given(productListingOptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ListingStatusResponse.OptionStatus result = service.linkMarketOption(CELL_ID, 50L, "V-1");

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        ProductListingOption saved = captor.getValue();
        assertThat(saved.getPlatformOptionId()).isEqualTo("V-1");
        assertThat(saved.getSellerProductItemId()).isEqualTo("S-1");
        assertThat(saved.getApprovalStatus()).isEqualTo(OptionApprovalStatus.APPROVED);
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("12900");
        // 🔴 Linking must never overwrite our selling price or option name with the market's values.
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("6000");
        assertThat(saved.getOptionName()).isEqualTo("기본");
        assertThat(result.getPlatformOptionId()).isEqualTo("V-1");
    }

    @Test
    void linkMarketOption_optionAlreadyIdentified_throws() {
        givenCellAndOptions(List.of(option().toBuilder().platformOptionId("V-9").build()));

        assertThatThrownBy(() -> service.linkMarketOption(CELL_ID, 50L, "V-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 쿠팡 옵션과 연결된 옵션입니다");
        verify(productListingOptionRepository, never()).save(any());
        verify(adapter, never()).fetchProduct(any(), any());
    }

    @Test
    void linkMarketOption_idHeldByAnotherOption_throws() {
        ProductListingOption other = ProductListingOption.builder().id(51L).optionName("다른")
                .platformOptionId("V-1").build();
        givenCellAndOptions(List.of(option(), other));

        assertThatThrownBy(() -> service.linkMarketOption(CELL_ID, 50L, "V-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("다른 옵션이 이미 사용 중인 쿠팡 옵션입니다");
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void linkMarketOption_idNotOnMarket_throws() {
        givenMarketCell(List.of(option()));

        assertThatThrownBy(() -> service.linkMarketOption(CELL_ID, 50L, "V-404"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("쿠팡에 없는 옵션입니다");
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void linkMarketOption_draftCell_throws() {
        given(productListingRepository.findScopedById(CELL_ID))
                .willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));

        assertThatThrownBy(() -> service.linkMarketOption(CELL_ID, 50L, "V-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("미등록");
        verifyNoInteractions(adapter);
    }

    @Test
    void listMarketOptions_marksOptionsAlreadyLinked() {
        givenMarketCell(List.of(ProductListingOption.builder().id(50L).optionName("12개")
                .platformOptionId("V-2").build()));

        List<MarketOptionResponse> result = service.listMarketOptions(CELL_ID);

        assertThat(result).hasSize(2);
        MarketOptionResponse first = result.stream().filter(r -> "V-1".equals(r.getVendorItemId())).findFirst().orElseThrow();
        MarketOptionResponse second = result.stream().filter(r -> "V-2".equals(r.getVendorItemId())).findFirst().orElseThrow();
        assertThat(first.getLinkedOptionId()).isNull();
        assertThat(first.getSalePrice()).isEqualByComparingTo("12900");
        assertThat(second.getLinkedOptionId()).isEqualTo(50L);
        assertThat(second.getLinkedOptionName()).isEqualTo("12개");
        verify(productListingOptionRepository, never()).save(any());
    }
}
