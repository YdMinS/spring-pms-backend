package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.claim.ClaimOrderBackfillService;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OrderSyncFacadeImpl — 호출 순서(ordersheets→cancels), 셀러 범위, 계정 격리, not-found 검증.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncFacadeImplTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CoupangOrderSyncService coupangOrderSyncService;
    @Mock private CoupangReturnSyncService coupangReturnSyncService;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private ClaimOrderBackfillService claimOrderBackfillService;

    @Mock private ClaimSyncAdapter claimSyncAdapter;

    /**
     * ⚠️ @InjectMocks 를 쓰지 않는다 — 생성자의 {@code List<ClaimSyncAdapter>} 에는 목이 주입되지 않아
     * null 이 들어가고, 파사드의 격리 try/catch 가 그 NPE 를 삼켜 어댑터 호출이 조용히 사라진다.
     * 기본은 빈 리스트(= local/test 프로파일과 같은 상태)이고, 어댑터가 필요한 테스트만 직접 넣는다.
     */
    private final List<ClaimSyncAdapter> claimSyncAdapters = new ArrayList<>();

    private OrderSyncFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new OrderSyncFacadeImpl(marketplaceAccountRepository, coupangOrderSyncService,
                coupangReturnSyncService, syncStatusRecorder, claimOrderBackfillService, claimSyncAdapters);
    }

    private MarketplaceAccount account(Long id) {
        return MarketplaceAccount.builder()
                .id(id).platform("COUPANG").vendorId("V" + id)
                .accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void sync_runsOrderThenCancel() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(3, 1, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(2, 1));

        OrderSyncResult result = facade.sync(1L);

        InOrder order = inOrder(coupangOrderSyncService, coupangReturnSyncService);
        order.verify(coupangOrderSyncService).syncAccount(acc, OrderSyncScope.FULL);   // ordersheets 먼저
        order.verify(coupangReturnSyncService).syncCancels(acc);  // 그 다음 취소 보정
        assertThat(result.newOrders()).isEqualTo(3);
        assertThat(result.updatedOrders()).isEqualTo(1);
        assertThat(result.canceledUpdated()).isEqualTo(2);
    }

    @Test
    void sync_backfillThrows_stillRecordsSuccessAndKeepsCounts() {
        // 백필은 정확도 보정이라 실패해도 주문·취소 결과를 깨지 않는다(취소 보정과 다른 판단).
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(3, 1, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(2, 1));
        given(claimOrderBackfillService.backfill(acc)).willThrow(new RuntimeException("쿠팡 500"));

        OrderSyncResult result = facade.sync(1L);

        verify(syncStatusRecorder).recordSuccess(1L);
        assertThat(result.newOrders()).isEqualTo(3);
        assertThat(result.updatedOrders()).isEqualTo(1);
        assertThat(result.canceledUpdated()).isEqualTo(2);
    }

    @Test
    void sync_recordsClaimSyncCompleted_afterTrackingSucceeds() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);

        verify(coupangReturnSyncService).trackOpenClaims(acc);
        verify(syncStatusRecorder).recordClaimSyncCompleted(1L);
        verify(syncStatusRecorder).recordSuccess(1L);
    }

    @Test
    void sync_trackingThrows_skipsClaimSyncRecordButKeepsSuccess() {
        // lastClaimSyncAt 미갱신 → 다음 회차 창이 자동으로 넓어져 놓친 구간을 덮는다(D18).
        // 회차 자체는 성공이다 — 추적은 이미 적재된 건의 상태 따라잡기라 취소 보정과 판단이 다르다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(coupangReturnSyncService.trackOpenClaims(acc)).willThrow(new RuntimeException("쿠팡 500"));

        facade.sync(1L);

        verify(syncStatusRecorder, never()).recordClaimSyncCompleted(any());
        verify(syncStatusRecorder).recordSuccess(1L);
    }

    @Test
    void sync_orderPartial_stillRecordsClaimSyncCompleted() {
        // 클레임 단계는 주문 PARTIAL 과 독립이다 — 적재는 returnRequests 경로라 ordersheets 상태 실패와
        // 무관하고, 여기서 미갱신하면 멀쩡히 적재된 구간을 다음 회차가 다시 읽는다(D18).
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL))
                .willReturn(new SyncResult(1, 0, 1, List.of(CoupangOrderStatus.INSTRUCT)));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);

        verify(syncStatusRecorder).recordPartial(eq(1L), anyString(), eq(false), eq(true));
        verify(syncStatusRecorder).recordClaimSyncCompleted(1L);
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void sync_withActiveScope_passesScopeThroughAndStillRunsCancelsAndRecordsSuccess() {
        // D5·D6: 조회 상태만 좁아지고 취소 보정·상태 기록은 그대로 돈다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(2, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(1, 1));

        OrderSyncResult result = facade.sync(1L, OrderSyncScope.ACTIVE);

        verify(coupangOrderSyncService).syncAccount(acc, OrderSyncScope.ACTIVE);
        verify(coupangReturnSyncService).syncCancels(acc);   // 범위와 무관(D5)
        verify(syncStatusRecorder).recordSuccess(1L);        // 범위와 무관(D6)
        assertThat(result.newOrders()).isEqualTo(2);
        assertThat(result.canceledUpdated()).isEqualTo(1);
    }

    @Test
    void syncBySeller_syncsOnlyThatSellersActiveAccounts() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(100L))
                .willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(any(), eq(OrderSyncScope.FULL))).willReturn(new SyncResult(1, 0, 0, List.of()));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncBySeller(100L);

        // 셀러 100의 활성 계정 2개만 동기화 (findByIsActiveTrue 전체조회 미사용)
        verify(marketplaceAccountRepository).findBySeller_IdAndIsActiveTrue(100L);
        verify(marketplaceAccountRepository, never()).findByIsActiveTrue();
        verify(coupangOrderSyncService).syncAccount(a1, OrderSyncScope.FULL);
        verify(coupangOrderSyncService).syncAccount(a2, OrderSyncScope.FULL);
        assertThat(result.newOrders()).isEqualTo(2);   // 1 + 1 합산
    }

    @Test
    void syncAll_isolatesAccountFailure() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        // a1 실패, a2 성공 → 전체 롤백 아님, a2 결과는 반영
        when(coupangOrderSyncService.syncAccount(a1, OrderSyncScope.FULL)).thenThrow(new RuntimeException("coupang down"));
        when(coupangOrderSyncService.syncAccount(a2, OrderSyncScope.FULL)).thenReturn(new SyncResult(5, 0, 0, List.of()));
        given(coupangReturnSyncService.syncCancels(a2)).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncAll();

        assertThat(result.newOrders()).isEqualTo(5);                 // a2만 반영
        verify(coupangReturnSyncService, never()).syncCancels(a1);   // a1은 ordersheets에서 끊김
    }

    @Test
    void syncOne_recordsFailure_whenOrdersThrow() {
        MarketplaceAccount acc = account(1L);
        RuntimeException boom = new RuntimeException("coupang down");
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        when(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).thenThrow(boom);

        assertThatThrownBy(() -> facade.sync(1L)).isSameAs(boom);   // 단건은 전파(D4)

        verify(syncStatusRecorder).recordFailure(1L, boom);
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void syncOne_recordsPartial_whenCancelThrows() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(1, 0, 1, List.of()));
        when(coupangReturnSyncService.syncCancels(acc)).thenThrow(new RuntimeException("cancel down"));

        assertThatThrownBy(() -> facade.sync(1L)).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        // 주문은 온전히 성공(orderDone=true), 취소 보정만 실패(cancelDone=false)
        verify(syncStatusRecorder).recordPartial(eq(1L), reason.capture(), eq(true), eq(false));
        assertThat(reason.getValue()).startsWith("취소 보정 실패 — ");
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void syncOne_recordsPartial_whenOrderStatusesPartiallyFail() {
        // D18 회귀: 일부 상태만 실패하면 예외가 아니라 failedStatuses 로 온다 → SUCCESS 로 낙인 금지
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL))
                .willReturn(new SyncResult(1, 0, 1, List.of(CoupangOrderStatus.FINAL_DELIVERY)));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 0));

        facade.sync(1L);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        // 주문 조회는 미완료(false), 취소 보정만 완료(true)
        verify(syncStatusRecorder).recordPartial(eq(1L), reason.capture(), eq(false), eq(true));
        assertThat(reason.getValue()).contains("FINAL_DELIVERY");
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void sync_exchangeAdapterThrows_stillRecordsSuccessAndRunsBackfill() {
        // 교환은 신규 연동이다 — 실패해도 주문·취소·반품(Stage A)을 되돌리지 않는다(PLAN §9).
        MarketplaceAccount acc = account(1L);
        claimSyncAdapters.add(claimSyncAdapter);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(claimSyncAdapter.platform()).willReturn("COUPANG");
        given(claimSyncAdapter.syncExchanges(acc)).willThrow(new RuntimeException("쿠팡 500"));

        facade.sync(1L);

        verify(claimOrderBackfillService).backfill(acc);      // 교환 실패가 백필을 막지 않는다
        verify(syncStatusRecorder).recordSuccess(1L);
    }

    @Test
    void sync_platformWithoutAdapter_skipsExchangeSyncSilently() {
        // 어댑터가 없는 플랫폼(네이버)은 조용히 건너뛴다 — orElseThrow 금지.
        MarketplaceAccount acc = account(1L);
        claimSyncAdapters.add(claimSyncAdapter);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.FULL)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(claimSyncAdapter.platform()).willReturn("NAVER");

        facade.sync(1L);

        verify(claimSyncAdapter, never()).syncExchanges(any());
        verify(claimOrderBackfillService).backfill(acc);
        verify(syncStatusRecorder).recordSuccess(1L);
    }

    @Test
    void syncPeriod_callsSyncAccountWithWindow() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class)))
                .willReturn(new SyncResult(4, 2, 1, List.of()));

        OrderSyncResult result = facade.syncPeriod(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ArgumentCaptor<SyncWindow> window = ArgumentCaptor.forClass(SyncWindow.class);
        verify(coupangOrderSyncService).syncAccount(eq(acc), window.capture());
        assertThat(window.getValue().from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window.getValue().to()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(result.newOrders()).isEqualTo(4);
        assertThat(result.updatedOrders()).isEqualTo(2);
        assertThat(result.canceledUpdated()).isZero();          // 취소 보정 없음(D4)
    }

    @Test
    void syncPeriod_doesNotRunCancelSync() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class)))
                .willReturn(new SyncResult(1, 0, 1, List.of()));

        facade.syncPeriod(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        verifyNoInteractions(coupangReturnSyncService);         // D4
    }

    @Test
    void syncPeriod_doesNotRecordSyncStatus() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class)))
                .willReturn(new SyncResult(1, 0, 1, List.of()));

        facade.syncPeriod(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        verifyNoInteractions(syncStatusRecorder);               // D5 — 과거 백필이 배너를 덮지 않는다
    }

    @Test
    void syncPeriod_nonCoupangAccount_throws() {
        MarketplaceAccount naver = MarketplaceAccount.builder()
                .id(9L).platform("NAVER").vendorId("V9")
                .accessKey("ak").secretKey("sk").isActive(true).build();
        given(marketplaceAccountRepository.findById(9L)).willReturn(Optional.of(naver));

        assertThatThrownBy(() -> facade.syncPeriod(9L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(coupangOrderSyncService, never()).syncAccount(any(), any(SyncWindow.class));
    }

    @Test
    void syncPeriod_propagatesFailure() {
        MarketplaceAccount acc = account(1L);
        RuntimeException boom = new RuntimeException("coupang 504");
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        when(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class))).thenThrow(boom);

        // 계정 단위 격리는 호출자(프론트 순차 루프)가 담당한다(D9).
        assertThatThrownBy(() -> facade.syncPeriod(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isSameAs(boom);
    }

    @Test
    void syncPeriod_accountNotFound_throws() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> facade.syncPeriod(999L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sync_accountNotFound_throws() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> facade.sync(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
