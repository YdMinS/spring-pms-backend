package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        return ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(status).platformProductId(platformProductId).build();
    }

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    private ProductListingOption option() {
        return ProductListingOption.builder().id(50L).optionName("기본")
                .sellingPrice(new BigDecimal("6000")).build();   // @Builder.Default approvalStatus = NOT_APPROVED
    }

    private void stubAccountAndAdapter() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq("COUPANG")))
                .willReturn(Optional.of(account()));
        given(resolver.resolve("COUPANG")).willReturn(adapter);
    }

    // (a) register happy: DRAFT + gen → SUBMITTED + platformProductId; options untouched.
    @Test
    void register_draftWithGen_promotesToSubmitted_optionsUnchanged() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        stubAccountAndAdapter();
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

    // account resolution: none → 404 (ResourceNotFoundException).
    @Test
    void register_accountMissing_throwsNotFound() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell(ListingStatus.DRAFT, null)));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq("COUPANG")))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CELL_ID)).isInstanceOf(ResourceNotFoundException.class);
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
        ProductListing ok = ProductListing.builder().id(1L).platform("COUPANG").name("ok")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-1").build();
        ProductListing boom = ProductListing.builder().id(2L).platform("COUPANG").name("boom")
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SUBMITTED).platformProductId("SP-2").build();
        given(productListingRepository.findPendingApproval()).willReturn(List.of(ok, boom));
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(ok));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(boom));
        lenient().when(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), anyString()))
                .thenReturn(Optional.of(account()));
        given(resolver.resolve("COUPANG")).willReturn(adapter);
        given(adapter.fetchStatus(eq(ok), any())).willReturn(new FetchResult(ListingStatus.SELLING, List.of()));
        given(adapter.fetchStatus(eq(boom), any())).willThrow(new RuntimeException("coupang 500"));
        lenient().when(productListingOptionRepository.findByProductListingId(1L)).thenReturn(List.of());

        ListingSyncResponse response = service.syncApprovals();

        assertThat(response.getSwept()).isEqualTo(2);
        assertThat(response.getPromotedToSelling()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(1);
    }
}
