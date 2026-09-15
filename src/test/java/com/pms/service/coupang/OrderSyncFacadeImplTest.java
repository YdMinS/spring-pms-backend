package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.claim.ClaimOrderBackfillService;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.inquiry.InquirySyncAdapter;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.AccountSyncLock.SyncWork;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OrderSyncFacadeImpl — 호출 순서(ordersheets→cancels), 셀러 범위, 계정 격리, 테넌트 처리, not-found 검증.
 *
 * <p>계정 병렬 실행(FEATURE_2609_46 / 03)은 <b>동기 실행기</b>를 주입해 결정적으로 검증한다 —
 * 병렬 타이밍 자체를 테스트로 재현하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncFacadeImplTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CoupangOrderSyncService coupangOrderSyncService;
    @Mock private CoupangReturnSyncService coupangReturnSyncService;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private ClaimOrderBackfillService claimOrderBackfillService;

    @Mock private ClaimSyncAdapter claimSyncAdapter;

    @Mock private InquirySyncAdapter inquirySyncAdapter;

    private static final Instant LOCK_T0 = Instant.parse("2026-09-15T00:00:00Z");

    /**
     * ⚠️ @InjectMocks 를 쓰지 않는다 — 생성자의 {@code List<ClaimSyncAdapter>} 에는 목이 주입되지 않아
     * null 이 들어가고, 파사드의 격리 try/catch 가 그 NPE 를 삼켜 어댑터 호출이 조용히 사라진다.
     * 기본은 빈 리스트(= local/test 프로파일과 같은 상태)이고, 어댑터가 필요한 테스트만 직접 넣는다.
     */
    private final List<ClaimSyncAdapter> claimSyncAdapters = new ArrayList<>();

    /** 문의 어댑터도 같은 이유로 빈 리스트가 기본이다(2609_23 D12). */
    private final List<InquirySyncAdapter> inquirySyncAdapters = new ArrayList<>();

    private OrderSyncFacadeImpl facade;

    /**
     * 🔴 락은 목이 아니라 실인스턴스다(FEATURE_2609_48 / Step 7-2) — 테스트가 미리 {@code tryAcquire} 해
     * "이미 돌고 있는" 상태를 만든다. 스레드로 동시성을 재현하지 않는다.
     * 테스트마다 새로 만들어지므로 잡아둔 락이 다음 테스트로 새지 않는다.
     */
    private AccountSyncLock accountSyncLock;

    @BeforeEach
    void setUp() {
        // 파사드와 락이 같은 설정 인스턴스를 본다 — 따로 만들면 설정값 변경이 한쪽에만 먹는다.
        CoupangProperties coupangProperties = new CoupangProperties();
        accountSyncLock = new AccountSyncLock(coupangProperties, new MutableClock(LOCK_T0));
        facade = new OrderSyncFacadeImpl(marketplaceAccountRepository, coupangProperties,
                coupangOrderSyncService, coupangReturnSyncService, syncStatusRecorder,
                claimOrderBackfillService, claimSyncAdapters, inquirySyncAdapters,
                new SameThreadExecutorService(), accountSyncLock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();   // 테스트가 세팅한 요청 테넌트를 다음 테스트로 흘리지 않는다
    }

    private MarketplaceAccount account(Long id) {
        return account(id, 1L);
    }

    private MarketplaceAccount account(Long id, Long tenantId) {
        return MarketplaceAccountFixture.coupangStubBuilder("V" + id, null)
                .id(id).platform(Platform.COUPANG).tenantId(tenantId)
                .isActive(true).build();
    }

    /** 제출 스레드에서 그대로 실행하는 실행기 — 병렬 타이밍을 없애 결정적으로 만든다. */
    private static final class SameThreadExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @Test
    void sync_runsOrderThenCancel() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(3, 1, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(2, 1));

        OrderSyncResult result = facade.sync(1L);

        InOrder order = inOrder(coupangOrderSyncService, coupangReturnSyncService);
        order.verify(coupangOrderSyncService).syncAccount(acc, OrderSyncScope.ACTIVE);   // ordersheets 먼저
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(3, 1, 1, List.of()));
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(1, 0, 1, List.of(CoupangOrderStatus.INSTRUCT)));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);

        verify(syncStatusRecorder).recordPartial(eq(1L), anyString(), eq(false), eq(true));
        verify(syncStatusRecorder).recordClaimSyncCompleted(1L);
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    // ---------------------------------------------------------------------
    // 프리셋 (FEATURE_2609_49 / D6·D7) — 갈리는 건 주문 조회뿐이다
    // ---------------------------------------------------------------------

    @Test
    void sync_quickPreset_usesActiveScopeAndStillRunsCancelsAndRecordsSuccess() {
        // D6·D7: 조회 상태만 좁아지고 취소 보정·상태 기록은 그대로 돈다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(2, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(1, 1));

        OrderSyncResult result = facade.sync(1L, OrderSyncPreset.QUICK);

        verify(coupangOrderSyncService).syncAccount(acc, OrderSyncScope.ACTIVE);
        // QUICK 은 앵커 기반 창 오버로드를 쓰지 않는다 — 활성 상태 창은 recent(sync-days) 고정이다.
        verify(coupangOrderSyncService, never()).syncAccount(any(), any(SyncWindow.class));
        verify(coupangReturnSyncService).syncCancels(acc);   // 프리셋과 무관(D7)
        verify(syncStatusRecorder).recordSuccess(1L);        // 프리셋과 무관(D7)
        assertThat(result.newOrders()).isEqualTo(2);
        assertThat(result.canceledUpdated()).isEqualTo(1);
    }

    @Test
    void sync_reconcilePreset_usesFixedFourteenDayWindowNotAnchor() {
        // 🔴 D7-1: QUICK 이 15분마다 lastOrderSyncAt 을 찍어 종결 창이 3일로 붕괴하므로, 야간 전량은
        // 앵커를 쓰는 scope 오버로드가 아니라 sync-days 고정 창 오버로드를 쓴다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class)))
                .willReturn(new SyncResult(4, 1, 6, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.sync(1L, OrderSyncPreset.RECONCILE);

        ArgumentCaptor<SyncWindow> window = ArgumentCaptor.forClass(SyncWindow.class);
        verify(coupangOrderSyncService).syncAccount(eq(acc), window.capture());
        LocalDate today = LocalDate.now(SyncWindow.KST);
        assertThat(window.getValue().to()).isEqualTo(today);
        assertThat(window.getValue().from()).isEqualTo(today.minusDays(14));   // CoupangProperties 기본 sync-days
        verify(coupangOrderSyncService, never()).syncAccount(any(), any(OrderSyncScope.class));
        assertThat(result.newOrders()).isEqualTo(4);
    }

    @Test
    void sync_reconcilePreset_stillRunsEveryStage() {
        // 단계(취소 보정·반품 추적·교환·백필·문의)는 프리셋과 무관하게 전부 돈다(D7).
        MarketplaceAccount acc = account(1L);
        claimSyncAdapters.add(claimSyncAdapter);
        inquirySyncAdapters.add(inquirySyncAdapter);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(eq(acc), any(SyncWindow.class)))
                .willReturn(new SyncResult(1, 0, 6, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(claimSyncAdapter.platform()).willReturn(Platform.COUPANG);
        given(inquirySyncAdapter.platform()).willReturn(Platform.COUPANG);

        facade.sync(1L, OrderSyncPreset.RECONCILE);

        verify(coupangReturnSyncService).syncCancels(acc);
        verify(coupangReturnSyncService).trackOpenClaims(acc);
        verify(claimSyncAdapter).syncExchanges(acc);
        verify(claimOrderBackfillService).backfill(acc);
        verify(inquirySyncAdapter).syncInquiries(acc);
        verify(syncStatusRecorder).recordSuccess(1L);
    }

    @Test
    void syncAll_withReconcilePreset_runsEveryAccountWithFixedWindow() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(any(), any(SyncWindow.class)))
                .willReturn(new SyncResult(1, 0, 6, List.of()));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncAll(OrderSyncPreset.RECONCILE);

        verify(coupangOrderSyncService).syncAccount(eq(a1), any(SyncWindow.class));
        verify(coupangOrderSyncService).syncAccount(eq(a2), any(SyncWindow.class));
        verify(coupangOrderSyncService, never()).syncAccount(any(), any(OrderSyncScope.class));
        assertThat(result.newOrders()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // 스케줄 회차는 실패를 낙인하지 않는다 (FEATURE_2609_49 / D13)
    // ---------------------------------------------------------------------

    @Test
    void scheduledRun_orderSyncThrows_doesNotRecordFailure() {
        // 🔴 누르지도 않은 사용자에게 (실패) 배너가 상주하는 것을 막는다 — 흔적은 WARN 로그뿐이다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(acc));
        when(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .thenThrow(new RuntimeException("coupang down"));

        facade.syncAll(OrderSyncPreset.QUICK);

        verify(syncStatusRecorder, never()).recordFailure(any(), any());
    }

    @Test
    void manualRun_orderSyncThrows_recordsFailure() {
        // 같은 예외라도 사용자가 직접 누른 회차는 기록한다 — 그 배너는 본인이 만든 것이다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        when(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .thenThrow(new RuntimeException("coupang down"));

        assertThatThrownBy(() -> facade.sync(1L, OrderSyncPreset.QUICK)).isInstanceOf(RuntimeException.class);

        verify(syncStatusRecorder).recordFailure(eq(1L), any());
    }

    @Test
    void scheduledRun_partialStatuses_doesNotRecordPartial() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(1, 0, 1, List.of(CoupangOrderStatus.INSTRUCT)));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.syncAll(OrderSyncPreset.QUICK);

        verify(syncStatusRecorder, never()).recordPartial(any(), anyString(), anyBoolean(), anyBoolean());
        verify(syncStatusRecorder, never()).recordSuccess(any());   // PARTIAL 을 SUCCESS 로 바꿔치지 않는다
    }

    @Test
    void scheduledRun_success_stillRecordsSuccessAndAnchors() {
        // 🔴 성공 기록은 스케줄 회차에서도 한다 — lastOrderSyncAt 이 배너·조회 창 앵커의 원천이다.
        MarketplaceAccount acc = account(1L);
        inquirySyncAdapters.add(inquirySyncAdapter);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(inquirySyncAdapter.platform()).willReturn(Platform.COUPANG);

        facade.syncAll(OrderSyncPreset.QUICK);

        verify(syncStatusRecorder).recordSuccess(1L);
        verify(syncStatusRecorder).recordClaimSyncCompleted(1L);
        verify(syncStatusRecorder).recordInquirySyncCompleted(1L);
    }

    @Test
    void syncBySeller_syncsOnlyThatSellersActiveAccounts() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(100L))
                .willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(any(), eq(OrderSyncScope.ACTIVE))).willReturn(new SyncResult(1, 0, 0, List.of()));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncBySeller(100L);

        // 셀러 100의 활성 계정 2개만 동기화 (findByIsActiveTrue 전체조회 미사용)
        verify(marketplaceAccountRepository).findBySeller_IdAndIsActiveTrue(100L);
        verify(marketplaceAccountRepository, never()).findByIsActiveTrue();
        verify(coupangOrderSyncService).syncAccount(a1, OrderSyncScope.ACTIVE);
        verify(coupangOrderSyncService).syncAccount(a2, OrderSyncScope.ACTIVE);
        assertThat(result.newOrders()).isEqualTo(2);   // 1 + 1 합산
    }

    @Test
    void syncAll_isolatesAccountFailure() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        // a1 실패, a2 성공 → 전체 롤백 아님, a2 결과는 반영
        when(coupangOrderSyncService.syncAccount(a1, OrderSyncScope.ACTIVE)).thenThrow(new RuntimeException("coupang down"));
        when(coupangOrderSyncService.syncAccount(a2, OrderSyncScope.ACTIVE)).thenReturn(new SyncResult(5, 0, 0, List.of()));
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
        when(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).thenThrow(boom);

        assertThatThrownBy(() -> facade.sync(1L)).isSameAs(boom);   // 단건은 전파(D4)

        verify(syncStatusRecorder).recordFailure(1L, boom);
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void syncOne_recordsPartial_whenCancelThrows() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(claimSyncAdapter.platform()).willReturn(Platform.COUPANG);
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
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));
        given(claimSyncAdapter.platform()).willReturn(Platform.NAVER);

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
        MarketplaceAccount naver = MarketplaceAccountFixture.coupangStubBuilder("V9", null)
                .id(9L).platform(Platform.NAVER)
                .isActive(true).build();
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
    void syncAll_aggregatesAllAccounts() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        MarketplaceAccount a3 = account(3L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2, a3));
        given(coupangOrderSyncService.syncAccount(any(), eq(OrderSyncScope.ACTIVE)))
                .willReturn(new SyncResult(1, 0, 0, List.of()));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        OrderSyncResult result = facade.syncAll();

        assertThat(result.newOrders()).isEqualTo(3);   // 병렬이어도 합산은 그대로
    }

    @Test
    void syncEach_clearsTenantAfterEachAccount() {
        // 풀 스레드는 복원이 아니라 clear 다(PLAN 2609_46 D8) — 남의 테넌트를 되살리면 교차 유출이다.
        MarketplaceAccount a1 = account(1L, 7L);
        MarketplaceAccount a2 = account(2L, 9L);
        List<Long> captured = new ArrayList<>();
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        willAnswer(inv -> {
            captured.add(TenantContext.get());
            return new SyncResult(1, 0, 0, List.of());
        }).given(coupangOrderSyncService).syncAccount(any(), eq(OrderSyncScope.ACTIVE));
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 1));

        facade.syncAll();

        assertThat(captured).containsExactly(7L, 9L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void syncOne_singleAccount_setsTenant() {
        MarketplaceAccount acc = account(1L, 9L);
        List<Long> captured = new ArrayList<>();
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        willAnswer(inv -> {
            captured.add(TenantContext.get());
            return new SyncResult(1, 0, 1, List.of());
        }).given(coupangOrderSyncService).syncAccount(acc, OrderSyncScope.ACTIVE);
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);

        assertThat(captured).containsExactly(9L);
    }

    @Test
    void sync_singleAccount_keepsRequestTenantAfterReturn() {
        // 웹 요청 스레드는 복원한다(D14) — clear 하면 직후 목록 조회가 빈 배열이 된다.
        MarketplaceAccount acc = account(1L, 9L);
        TenantContext.set(5L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);

        assertThat(TenantContext.get()).isEqualTo(5L);
    }

    @Test
    void sync_accountNotFound_throws() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> facade.sync(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------
    // 채널별 동기화 락 (FEATURE_2609_48) — 락을 미리 잡아 "이미 돌고 있는" 상태를 만든다
    // ---------------------------------------------------------------------

    @Test
    void sync_whenLockHeld_skipsWithoutCallingCoupang() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        accountSyncLock.tryAcquire(SyncWork.ORDER, 1L);          // 다른 요청이 이미 돌고 있다

        OrderSyncResult result = facade.sync(1L);

        verify(coupangOrderSyncService, never()).syncAccount(any(), any(OrderSyncScope.class));
        assertThat(result.skippedAccounts()).isEqualTo(1);
        // 건너뛴 회차는 성공도 실패도 아니다 — 기록하면 "마지막 동기화" 배너와 조회 창 앵커가 밀린다.
        verifyNoInteractions(syncStatusRecorder);
    }

    @Test
    void sync_releasesLock_soNextCallRuns() {
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        given(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        facade.sync(1L);
        OrderSyncResult second = facade.sync(1L);

        verify(coupangOrderSyncService, times(2)).syncAccount(acc, OrderSyncScope.ACTIVE);
        assertThat(second.skippedAccounts()).isZero();
    }

    @Test
    void sync_releasesLockOnFailure() {
        // 🔴 실패 직후 재시도가 락에 막히면 안 된다 — try-with-resources 가 예외 전파 전에 푼다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        when(coupangOrderSyncService.syncAccount(acc, OrderSyncScope.ACTIVE))
                .thenThrow(new RuntimeException("coupang down"))
                .thenReturn(new SyncResult(2, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(acc)).willReturn(new CancelSyncResult(0, 1));

        assertThatThrownBy(() -> facade.sync(1L)).isInstanceOf(RuntimeException.class);
        OrderSyncResult second = facade.sync(1L);

        assertThat(second.newOrders()).isEqualTo(2);
        assertThat(second.skippedAccounts()).isZero();
    }

    @Test
    void syncAll_oneChannelBusy_otherStillRuns() {
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(a2, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(3, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(a2)).willReturn(new CancelSyncResult(0, 1));
        accountSyncLock.tryAcquire(SyncWork.ORDER, 1L);

        OrderSyncResult result = facade.syncAll();

        verify(coupangOrderSyncService, never()).syncAccount(eq(a1), any(OrderSyncScope.class));
        verify(coupangOrderSyncService).syncAccount(a2, OrderSyncScope.ACTIVE);
        assertThat(result.skippedAccounts()).isEqualTo(1);
        assertThat(result.newOrders()).isEqualTo(3);
    }

    @Test
    void syncAll_lastChannelSkipped_keepsRealSyncedAt() {
        // 🔴 D9 — 마지막 계정이 건너뛰어도 합계 시각은 실제로 조회한 계정의 시각이어야 한다.
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        given(coupangOrderSyncService.syncAccount(a1, OrderSyncScope.ACTIVE)).willReturn(new SyncResult(1, 0, 1, List.of()));
        given(coupangReturnSyncService.syncCancels(a1)).willReturn(new CancelSyncResult(0, 1));
        accountSyncLock.tryAcquire(SyncWork.ORDER, 2L);

        OrderSyncResult result = facade.syncAll();

        assertThat(result.syncedAt()).isNotNull();
        assertThat(result.newOrders()).isEqualTo(1);
        assertThat(result.skippedAccounts()).isEqualTo(1);
    }

    @Test
    void syncAll_allChannelsSkipped_syncedAtIsNull() {
        // 🔴 D9 — 누산 씨앗 empty() 가 now() 였다면 여기서 가짜 시각이 남는다.
        MarketplaceAccount a1 = account(1L);
        MarketplaceAccount a2 = account(2L);
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(a1, a2));
        accountSyncLock.tryAcquire(SyncWork.ORDER, 1L);
        accountSyncLock.tryAcquire(SyncWork.ORDER, 2L);

        OrderSyncResult result = facade.syncAll();

        assertThat(result.syncedAt()).isNull();
        assertThat(result.skippedAccounts()).isEqualTo(2);
        verifyNoInteractions(coupangOrderSyncService);
    }

    @Test
    void syncPeriod_whenOrderLockHeld_skips() {
        // D6 — 과거 달 불러오기도 정기 동기화와 같은 열쇠를 쓴다.
        MarketplaceAccount acc = account(1L);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acc));
        accountSyncLock.tryAcquire(SyncWork.ORDER, 1L);

        OrderSyncResult result = facade.syncPeriod(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        verify(coupangOrderSyncService, never()).syncAccount(any(), any(SyncWindow.class));
        assertThat(result.skippedAccounts()).isEqualTo(1);
    }
}
