package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.MasterApprovalRefreshResponse;
import com.pms.dto.response.MasterApprovalRefreshResponse.Outcome;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_38 / D6 · D7 · D8 · D9 · D10: the master-wide approval refresh loop. */
@ExtendWith(MockitoExtension.class)
class MasterApprovalRefreshServiceTest {

    private static final Long MASTER_ID = 10L;

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ListingRegistrationService listingRegistrationService;
    @InjectMocks private MasterApprovalRefreshServiceImpl service;

    private static ProductListing cell(long id, long sellerId, Platform platform, ListingStatus status) {
        return ProductListing.builder().id(id).platform(platform).status(status).platformProductId("P-" + id)
                .seller(Seller.builder().id(sellerId).build()).build();
    }

    private void master(ProductListing... cells) {
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).build()));
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cells));
    }

    @Test
    void refresh_unknownMaster_throws404_andCallsNothing() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(MASTER_ID, List.of(1L)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(listingRegistrationService, never()).fetchStatus(any());
    }

    @Test
    void refresh_idOutsideTheMaster_throws400_andCallsNothing() {
        master(cell(1L, 7L, Platform.COUPANG, ListingStatus.SUBMITTED));

        assertThatThrownBy(() -> service.refresh(MASTER_ID, List.of(1L, 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
        verify(listingRegistrationService, never()).fetchStatus(any());
    }

    @Test
    void refresh_linesInReceivedOrder_skipsNonTargetStatusAndUnsupportedMarket_isolatesFailure() {
        master(cell(1L, 7L, Platform.COUPANG, ListingStatus.SUBMITTED),
                cell(2L, 7L, Platform.COUPANG, ListingStatus.SUSPENDED),
                cell(3L, 8L, Platform.NAVER, ListingStatus.SELLING),
                cell(4L, 9L, Platform.ELEVENST, ListingStatus.REJECTED));
        given(listingRegistrationService.fetchStatus(1L)).willReturn(ListingStatusResponse.builder()
                .productListingId(1L).status("REJECTED").reviewNote("상품정보제공고시 누락").reviewNoteState("FOUND")
                .build());
        given(listingRegistrationService.fetchStatus(4L)).willThrow(new IllegalArgumentException("미등록"));

        MasterApprovalRefreshResponse res = service.refresh(MASTER_ID, List.of(4L, 3L, 2L, 1L));

        assertThat(res.getRequested()).isEqualTo(4);
        assertThat(res.getRefreshed()).isEqualTo(1);
        assertThat(res.getSkipped()).isEqualTo(2);
        assertThat(res.getFailed()).isEqualTo(1);
        assertThat(res.getResults()).extracting(MasterApprovalRefreshResponse.Result::getProductListingId)
                .containsExactly(4L, 3L, 2L, 1L);
        assertThat(res.getResults()).extracting(MasterApprovalRefreshResponse.Result::getOutcome)
                .containsExactly(Outcome.FAILED, Outcome.SKIPPED, Outcome.SKIPPED, Outcome.REFRESHED);
        assertThat(res.getResults()).extracting(MasterApprovalRefreshResponse.Result::getMessage)
                .containsExactly("미등록", "새로고침을 지원하지 않는 마켓", "새로고침 대상 상태가 아님", null);
        assertThat(res.getResults().get(3).getStatus()).isEqualTo("REJECTED");
        assertThat(res.getResults().get(3).getReviewNote()).isEqualTo("상품정보제공고시 누락");
        assertThat(res.getResults().get(3).getReviewNoteState()).isEqualTo("FOUND");
        verify(listingRegistrationService, never()).fetchStatus(2L);
        verify(listingRegistrationService, never()).fetchStatus(3L);
    }

    @Test
    void refresh_rateLimit_skipsTheRestOfThatSellerAndMarketOnly() {
        master(cell(1L, 7L, Platform.COUPANG, ListingStatus.SUBMITTED),
                cell(2L, 7L, Platform.COUPANG, ListingStatus.SELLING),
                cell(3L, 8L, Platform.COUPANG, ListingStatus.SUBMITTED));
        given(listingRegistrationService.fetchStatus(1L)).willThrow(new CoupangRateLimitedException(Instant.now()));
        given(listingRegistrationService.fetchStatus(3L)).willReturn(ListingStatusResponse.builder()
                .productListingId(3L).status("SELLING").build());

        MasterApprovalRefreshResponse res = service.refresh(MASTER_ID, List.of(1L, 2L, 3L));

        assertThat(res.getResults()).extracting(MasterApprovalRefreshResponse.Result::getOutcome)
                .containsExactly(Outcome.FAILED, Outcome.SKIPPED, Outcome.REFRESHED);
        assertThat(res.getResults().get(1).getMessage()).isEqualTo("쿠팡 호출 한도 초과 — 잠시 뒤 다시 시도");
        verify(listingRegistrationService, never()).fetchStatus(2L);
    }
}
