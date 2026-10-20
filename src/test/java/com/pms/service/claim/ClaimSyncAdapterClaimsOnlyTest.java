package com.pms.service.claim;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.claim.ClaimSyncAdapter.ClaimSyncResult;
import com.pms.service.coupang.CoupangReturnSyncService;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.elevenst.ElevenstOrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ClaimSyncAdapter#syncClaimsOnly} — the claims-only rounds moved from {@code ClaimSyncServiceImpl}
 * (FEATURE_2610_23 / D12; the behaviour of FEATURE_2609_70 / D14 · D15 · D15-1 and FEATURE_2610_17 / D15).
 *
 * <p>The adapters are spies over real instances so {@code syncExchanges} can be stubbed while the round runs for real.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSyncAdapterClaimsOnlyTest {

    @Mock private CoupangReturnSyncService coupangReturnSyncService;
    @Mock private ClaimOrderBackfillService claimOrderBackfillService;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private ElevenstOrderApi elevenstOrderApi;
    @Mock private ElevenstClaimUpserter elevenstClaimUpserter;
    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private ClaimStaleSweeper claimStaleSweeper;

    private CoupangClaimAdapter coupang;
    private ElevenstClaimSyncAdapter elevenst;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A0001", null)
            .id(7L).platform(Platform.COUPANG).tenantId(1L).build();

    @BeforeEach
    void setUp() {
        coupang = spy(new CoupangClaimAdapter(null, new CoupangProperties(), null, null, null, null, null, null,
                coupangReturnSyncService, claimOrderBackfillService, syncStatusRecorder));
        elevenst = spy(new ElevenstClaimSyncAdapter(elevenstOrderApi, elevenstClaimUpserter, orderClaimRepository,
                claimStaleSweeper, syncStatusRecorder));
    }

    @Test
    void coupang_runsTheClaimStagesInTheOrderRoundOrder() {
        // 🔴 syncCancels 가 신규 반품 적재를 겸한다(2609_18 D15) — 순서·존재 둘 다 회귀 대상이다.
        doReturn(new ClaimSyncResult(1, 0, 0)).when(coupang).syncExchanges(account);

        coupang.syncClaimsOnly(account);

        InOrder order = inOrder(coupangReturnSyncService, syncStatusRecorder, coupang, claimOrderBackfillService);
        order.verify(coupangReturnSyncService).syncCancels(account);
        order.verify(coupangReturnSyncService).trackOpenClaims(account);
        order.verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
        order.verify(coupang).syncExchanges(account);
        order.verify(claimOrderBackfillService).backfill(account);
    }

    @Test
    void coupang_trackingFails_propagatesAndDoesNotRecord() {
        // 실패한 회차에 시각을 갱신하면 놓친 구간이 다음 창에서 빠져 영구히 사라진다.
        willThrow(new IllegalStateException("쿠팡 추적 실패"))
                .given(coupangReturnSyncService).trackOpenClaims(account);

        assertThatThrownBy(() -> coupang.syncClaimsOnly(account)).isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder, never()).recordClaimSyncCompleted(anyLong());
        verify(claimOrderBackfillService, never()).backfill(any());
    }

    @Test
    void coupang_exchangeFails_isIsolated() {
        doThrow(new IllegalStateException("교환 조회 실패")).when(coupang).syncExchanges(account);

        coupang.syncClaimsOnly(account);

        verify(claimOrderBackfillService).backfill(account);   // 교환 실패가 백필을 건너뛰게 하지 않는다
        verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
    }

    @Test
    void coupang_backfillFails_isIsolated() {
        doReturn(new ClaimSyncResult(1, 0, 0)).when(coupang).syncExchanges(account);
        willThrow(new IllegalStateException("백필 실패")).given(claimOrderBackfillService).backfill(account);

        coupang.syncClaimsOnly(account);

        verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
    }

    @Test
    void coupang_neverStampsOrderSyncStatus() {
        // 🔴 클레임만 돌고 lastSyncStatus 를 SUCCESS 로 바꾸면 주문이 실패한 채널이 정상으로 보인다.
        doReturn(new ClaimSyncResult(1, 0, 0)).when(coupang).syncExchanges(account);

        coupang.syncClaimsOnly(account);

        verify(syncStatusRecorder, never()).recordSuccess(anyLong());
        verify(syncStatusRecorder, never()).recordPartial(anyLong(), anyString(), anyBoolean(), anyBoolean());
        verify(syncStatusRecorder, never()).recordFailure(anyLong(), any());
    }

    /** FEATURE_2610_17 / D15: the 11st round is its one load (returns + exchanges), then the stamp — no Coupang step. */
    @Test
    void elevenst_loadsOnceThenStampsClaimSync() {
        MarketplaceAccount eleven = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).tenantId(1L).build();
        doReturn(new ClaimSyncResult(6, 0, 0)).when(elevenst).syncExchanges(eleven);

        elevenst.syncClaimsOnly(eleven);

        InOrder order = inOrder(elevenst, syncStatusRecorder);
        order.verify(elevenst).syncExchanges(eleven);
        order.verify(syncStatusRecorder).recordClaimSyncCompleted(9L);
        verifyNoInteractions(coupangReturnSyncService, claimOrderBackfillService);
    }

    @Test
    void elevenst_loadFails_propagatesAndDoesNotRecord() {
        MarketplaceAccount eleven = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).tenantId(1L).build();
        doThrow(new IllegalStateException("11번가 조회 실패")).when(elevenst).syncExchanges(eleven);

        assertThatThrownBy(() -> elevenst.syncClaimsOnly(eleven)).isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder, never()).recordClaimSyncCompleted(anyLong());
    }
}
