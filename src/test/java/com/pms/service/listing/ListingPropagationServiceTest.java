package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.GrandTotalSend;
import com.pms.domain.ListingStatus;
import com.pms.domain.ListingUpdateItem;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.PushSyncResponse;
import com.pms.fixture.MarketplaceAccountFixture;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Layer B (FEATURE_2608_06 / 3d): pushSync pushes pending on-market cells (→ SUBMITTED + dirty clear), skips
 * not-registered / not-pending cells, and isolates a per-cell adapter failure. FEATURE_2610_34: the 총 수량 state the
 * adapter reports is saved on the pushed cell.
 */
@ExtendWith(MockitoExtension.class)
class ListingPropagationServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel adapter;
    @Mock private TagMergeService tagMergeService;
    @InjectMocks private ListingPropagationServiceImpl service;

    private static final Long SELLER_ID = 7L;

    private ProductListing cell(Long id, String platformProductId, boolean pending) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("셀-" + id)
                .seller(Seller.builder().id(SELLER_ID).build())
                .status(ListingStatus.SELLING).platformProductId(platformProductId)
                .needsMarketSync(pending).build();
    }

    private MarketplaceAccount account() {
        return MarketplaceAccountFixture.coupangStubBuilder("V1", null).isActive(true).build();
    }

    private void stubAccountAndGenAndAdapter(Long cellId) {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq(Platform.COUPANG)))
                .willReturn(Optional.of(account()));
        given(generatedProductDataRepository.findByProductListingId(cellId))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
        // FEATURE_2610_34: reports nothing about 총 수량 (lenient — a test may stub its own outcome).
        lenient().when(adapter.updateAndReport(any(), any(), any(), any())).thenReturn(PushOutcome.unchanged(null));
    }

    // (a) happy: pending on-market cell with account + gen → adapter.updateAndReport + SUBMITTED + dirty cleared.
    @Test
    void pushSync_pendingCell_pushesAndClearsDirty() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);

        PushSyncResponse response = service.pushSync(List.of(1L));

        assertThat(response.getRequested()).isEqualTo(1);
        assertThat(response.getPushed()).isEqualTo(1);
        verify(adapter).updateAndReport(eq(pending), any(), any(), any());

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ListingStatus.SUBMITTED);
        assertThat(captor.getValue().isNeedsMarketSync()).isFalse();
    }

    // (b) skip guards: not registered (platformProductId null) → skip; not pending (needsMarketSync false) → skip.
    @Test
    void pushSync_skipsNotRegisteredAndNotPending() {
        ProductListing draft = cell(1L, null, true);          // not registered
        ProductListing notPending = cell(2L, "SP-2", false);  // already synced
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(draft));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(notPending));

        PushSyncResponse response = service.pushSync(List.of(1L, 2L));

        assertThat(response.getSkipped()).isEqualTo(2);
        assertThat(response.getPushed()).isZero();
        verify(adapter, never()).updateAndReport(any(), any(), any(), any());
        verify(productListingRepository, never()).save(any());
    }

    // (c) isolation: 2 pending; cell2 adapter.updateAndReport throws → cell1 pushed, failed=1.
    @Test
    void pushSync_isolatesPerCellFailure() {
        ProductListing ok = cell(1L, "SP-1", true);
        ProductListing boom = cell(2L, "SP-2", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(ok));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(boom));
        lenient().when(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(SELLER_ID), eq(Platform.COUPANG)))
                .thenReturn(Optional.of(account()));
        lenient().when(generatedProductDataRepository.findByProductListingId(any()))
                .thenReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
        // ok cell: reports nothing about 총 수량. Lenient so the ok cell's non-matching call to the same
        // mocked method does not trip strict-stubbing (which the service's try/catch would count as a failure).
        lenient().when(adapter.updateAndReport(eq(ok), any(), any(), any())).thenReturn(PushOutcome.unchanged(null));
        lenient().when(adapter.updateAndReport(eq(boom), any(), any(), any())).thenThrow(new RuntimeException("coupang 500"));

        PushSyncResponse response = service.pushSync(List.of(1L, 2L));

        assertThat(response.getPushed()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(1);
    }

    // FEATURE_2610_34 / D11 · D17: the reported 총 수량 state is saved on the pushed listing.
    @Test
    void pushSync_storesReportedGrandTotalState() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        given(adapter.updateAndReport(eq(pending), any(), any(), any()))
                .willReturn(new PushOutcome(null, GrandTotalSend.OMITTED, null, false));

        service.pushSync(List.of(1L));

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().getGrandTotalSend()).isEqualTo(GrandTotalSend.OMITTED);
    }

    // FEATURE_2610_36 / D11: the bundle type the adapter read for a listing that did not know it is saved.
    @Test
    void pushSync_storesReportedBundleType() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        given(adapter.updateAndReport(eq(pending), any(), any(), any()))
                .willReturn(new PushOutcome(null, null, null, false, "AB"));

        service.pushSync(List.of(1L));

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().getMarketBundleType()).isEqualTo("AB");
    }

    // (d) 42: after update, a deactivated APPROVED option is reverted to NOT_APPROVED; the active one is untouched.
    @Test
    void pushSync_deactivatedApprovedOption_revertedToNotApproved() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        ProductListingOption keptActive = ProductListingOption.builder().id(10L).optionName("A")
                .active(true).approvalStatus(OptionApprovalStatus.APPROVED).build();
        ProductListingOption deactivated = ProductListingOption.builder().id(11L).optionName("B")
                .active(false).approvalStatus(OptionApprovalStatus.APPROVED).build();
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(keptActive, deactivated));

        service.pushSync(List.of(1L));

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());   // exactly one option saved
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(OptionApprovalStatus.NOT_APPROVED);
    }

    // ---- FEATURE_2611_25: batch push = the D9 items · skip (D26) · no revert on Coupang (D23) · no on/off (D31) ----

    // D4 · D9: the batch push sends the default items, every active option line, may skip and stores no on/off.
    @Test
    void pushSync_passesBatchSelection() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);

        service.pushSync(List.of(1L));

        ArgumentCaptor<ListingUpdateSelection> captor = ArgumentCaptor.forClass(ListingUpdateSelection.class);
        verify(adapter).updateAndReport(eq(pending), any(), any(), captor.capture());
        assertThat(captor.getValue().overwrite()).isEqualTo(ListingUpdateItem.BATCH_DEFAULT);
        assertThat(captor.getValue().defaultOptions()).isTrue();
        assertThat(captor.getValue().skipWhenUnchanged()).isTrue();
        assertThat(captor.getValue().savesOptionChoice()).isFalse();
    }

    // D26: nothing sent → only the dirty marker is cleared, counted as skipped.
    @Test
    void pushSync_notSentOutcome_clearsDirtyOnlyAndCountsSkipped() {
        ProductListing pending = cell(1L, "SP-1", true).toBuilder()
                .grandTotalSend(GrandTotalSend.OMITTED).marketBundleType("AB").build();
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        given(adapter.updateAndReport(eq(pending), any(), any(), any())).willReturn(PushOutcome.notSent());

        PushSyncResponse response = service.pushSync(List.of(1L));

        assertThat(response.getPushed()).isZero();
        assertThat(response.getSkipped()).isEqualTo(1);
        assertThat(response.getFailed()).isZero();
        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        assertThat(captor.getValue().isNeedsMarketSync()).isFalse();
        assertThat(captor.getValue().getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(captor.getValue().getGrandTotalSend()).isEqualTo(GrandTotalSend.OMITTED);
        assertThat(captor.getValue().getMarketBundleType()).isEqualTo("AB");
        verify(tagMergeService, never()).recordRevisionIfChanged(any(), any());
        verify(productListingOptionRepository, never()).save(any());
    }

    // D23: a market that keeps inactive options (Coupang) → no revert to NOT_APPROVED.
    @Test
    void pushSync_marketKeepsInactiveOptions_noRevert() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        given(adapter.keepsInactiveOptionsOnUpdate()).willReturn(true);
        ProductListingOption keptActive = ProductListingOption.builder().id(10L).optionName("A")
                .active(true).approvalStatus(OptionApprovalStatus.APPROVED).build();
        ProductListingOption deactivated = ProductListingOption.builder().id(11L).optionName("B")
                .active(false).approvalStatus(OptionApprovalStatus.APPROVED).build();
        lenient().when(productListingOptionRepository.findByProductListingId(1L))
                .thenReturn(List.of(keptActive, deactivated));

        PushSyncResponse response = service.pushSync(List.of(1L));

        verify(productListingOptionRepository, never()).save(any());
        assertThat(response.getPushed()).isEqualTo(1);
    }

    // D31: a batch push never stores the on/off the adapter reported.
    @Test
    void pushSync_ignoresReportedOptionChoice() {
        ProductListing pending = cell(1L, "SP-1", true);
        given(productListingRepository.findScopedById(1L)).willReturn(Optional.of(pending));
        stubAccountAndGenAndAdapter(1L);
        given(adapter.keepsInactiveOptionsOnUpdate()).willReturn(true);
        given(adapter.updateAndReport(eq(pending), any(), any(), any()))
                .willReturn(new PushOutcome(null, null, null, false, null, false, Map.of(50L, false)));

        PushSyncResponse response = service.pushSync(List.of(1L));

        verify(productListingOptionRepository, never()).save(any());
        assertThat(response.getPushed()).isEqualTo(1);
    }
}
