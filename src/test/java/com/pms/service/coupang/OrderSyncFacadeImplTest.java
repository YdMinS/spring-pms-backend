package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderSyncFacadeImpl — 호출 순서(ordersheets→cancels), 셀러 범위, 계정 격리, not-found 검증.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncFacadeImplTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CoupangOrderSyncService coupangOrderSyncService;
    @Mock private CoupangReturnSyncService coupangReturnSyncService;

    @InjectMocks private OrderSyncFacadeImpl facade;

    private MarketplaceAccount account(Long id) {
        return MarketplaceAccount.builder()
                .id(id).platform("COUPANG").vendorId("V" + id)
                .accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void sync_runsOrderThenCancel() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc)).willReturn(new SyncResult(3, 1, 1));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(2, 1));

        OrderSyncResult result = facade.sync(1L);

        InOrder order = inOrder(coupangOrderSyncService, coupangReturnSyncService);
        order.verify(coupangOrderSyncService).syncAccount(acc);   // ordersheets 먼저
        order.verify(coupangReturnSyncService).syncCancels(acc);  // 그 다음 취소 보정
        assertThat(result.newOrders()).isEqualTo(3);
        assertThat(result.updatedOrders()).isEqualTo(1);
        assertThat(result.canceledUpdated()).isEqualTo(2);
    }

    @Test
    void syncBySeller_syncsOnlyThatSellersActiveAccounts() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(100L))
                .willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(any())).willReturn(new SyncResult(1, 0, 0));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncBySeller(100L);

        // 셀러 100의 활성 계정 2개만 동기화 (findByIsActiveTrue 전체조회 미사용)
        verify(marketplaceAccountRepository).findBySeller_IdAndIsActiveTrue(100L);
        verify(marketplaceAccountRepository, never()).findByIsActiveTrue();
        verify(coupangOrderSyncService).syncAccount(a1);
        verify(coupangOrderSyncService).syncAccount(a2);
        assertThat(result.newOrders()).isEqualTo(2);   // 1 + 1 합산
    }

    @Test
    void syncAll_isolatesAccountFailure() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        // a1 실패, a2 성공 → 전체 롤백 아님, a2 결과는 반영
        when(coupangOrderSyncService.syncAccount(a1)).thenThrow(new RuntimeException("coupang down"));
        when(coupangOrderSyncService.syncAccount(a2)).thenReturn(new SyncResult(5, 0, 0));
        given(coupangReturnSyncService.syncCancels(a2)).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncAll();

        assertThat(result.newOrders()).isEqualTo(5);                 // a2만 반영
        verify(coupangReturnSyncService, never()).syncCancels(a1);   // a1은 ordersheets에서 끊김
    }

    @Test
    void sync_accountNotFound_throws() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> facade.sync(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
