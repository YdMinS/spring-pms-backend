package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.SyncStatus;
import com.pms.dto.response.SyncTargetResponse;
import com.pms.repository.MarketplaceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SyncTargetServiceImpl — 대상 필터(활성 COUPANG)와 셀러 스코프 쿼리 선택 검증.
 */
@ExtendWith(MockitoExtension.class)
class SyncTargetServiceImplTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;

    @InjectMocks private SyncTargetServiceImpl service;

    private MarketplaceAccount account(Long id, String platform, String sellerName) {
        Seller seller = Seller.builder().id(id * 10).sellerName(sellerName).build();
        return MarketplaceAccount.builder()
                .id(id).seller(seller).platform(platform).accountAlias("별칭" + id)
                .vendorId("V" + id).accessKey("ak").secretKey("sk").isActive(true)
                .lastSyncStatus(SyncStatus.PARTIAL)
                .lastSyncAt(LocalDateTime.now())
                .lastSyncError("취소 보정 실패 — HTTP 504 from Coupang")
                .build();
    }

    @Test
    void list_filtersOutNonCoupangAndMapsSellerName() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(
                account(1L, "COUPANG", "셀러A"),
                account(2L, "COUPANG", "셀러B"),
                account(3L, "NAVER", "셀러C")));

        List<SyncTargetResponse> targets = service.list(null);

        assertThat(targets).hasSize(2);   // NAVER 제외 = syncEach 필터와 동일 기준
        assertThat(targets).extracting(SyncTargetResponse::getSellerName)
                .containsExactly("셀러A", "셀러B");
        assertThat(targets.get(0).getLastSyncStatus()).isEqualTo("PARTIAL");
        assertThat(targets.get(0).getLastSyncError()).isEqualTo("취소 보정 실패 — HTTP 504 from Coupang");
    }

    @Test
    void list_bySeller_usesSellerScopedQuery() {
        given(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(7L))
                .willReturn(List.of(account(1L, "COUPANG", "셀러A")));

        List<SyncTargetResponse> targets = service.list(7L);

        assertThat(targets).hasSize(1);
        verify(marketplaceAccountRepository).findBySeller_IdAndIsActiveTrue(7L);
        verify(marketplaceAccountRepository, never()).findByIsActiveTrue();
    }
}
