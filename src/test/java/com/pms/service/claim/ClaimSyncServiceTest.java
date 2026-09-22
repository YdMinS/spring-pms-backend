package com.pms.service.claim;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ClaimSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.AccountSyncLock;
import com.pms.service.coupang.CoupangReturnSyncService;
import com.pms.service.coupang.SyncStatusRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ClaimSyncService — 반품·교환만 다시 가져오기 (FEATURE_2609_70 / D14·D15·D15-1).
 *
 * 관심사는 넷이다: 파사드와 <b>같은 순서</b>로 기존 적재를 부르는가(재구현 금지) · 추적까지 끝난
 * 회차에만 마지막 시각을 찍는가 · 보정/추적 실패를 삼키지 않는가 · 이미 돌고 있으면 쿠팡을
 * 치지 않는가.
 *
 * <p>🔴 락은 목이 아니라 실인스턴스다(2609_48 Step 7-2 와 같은 방식) — 테스트가 미리
 * {@code tryAcquire} 해 "이미 돌고 있는" 상태를 만든다. 스레드로 동시성을 재현하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSyncServiceTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CoupangReturnSyncService coupangReturnSyncService;
    @Mock private ClaimOrderBackfillService claimOrderBackfillService;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private ClaimSyncAdapter claimSyncAdapter;

    /**
     * ⚠️ 목 리스트에 @Mock 이 자동 주입되지 않는다 — 어댑터가 필요한 테스트만 직접 넣는다
     * (OrderSyncFacadeImplTest 와 같은 이유).
     */
    private final List<ClaimSyncAdapter> claimSyncAdapters = new ArrayList<>();

    private AccountSyncLock accountSyncLock;
    private ClaimSyncServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A0001", null)
            .id(7L).platform(Platform.COUPANG).tenantId(1L).build();

    @BeforeEach
    void setUp() {
        accountSyncLock = new AccountSyncLock(new CoupangProperties(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
        service = new ClaimSyncServiceImpl(marketplaceAccountRepository, accountSyncLock,
                coupangReturnSyncService, claimSyncAdapters, claimOrderBackfillService,
                syncStatusRecorder);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();   // 테스트가 세팅한 요청 테넌트를 다음 테스트로 흘리지 않는다
    }

    private void withAccount() {
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
    }

    private void withExchangeAdapter() {
        given(claimSyncAdapter.platform()).willReturn(Platform.COUPANG);
        claimSyncAdapters.add(claimSyncAdapter);
    }

    @Test
    void sync_runsClaimStagesInFacadeOrder() {
        // 🔴 syncCancels 가 신규 반품 적재를 겸한다(2609_18 D15) — 순서·존재 둘 다 회귀 대상이다.
        withAccount();
        withExchangeAdapter();

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.accountId()).isEqualTo(7L);
        assertThat(response.skipped()).isFalse();
        assertThat(response.syncedAt()).isNotNull();

        InOrder order = inOrder(coupangReturnSyncService, claimSyncAdapter, claimOrderBackfillService);
        order.verify(coupangReturnSyncService).syncCancels(account);
        order.verify(coupangReturnSyncService).trackOpenClaims(account);
        order.verify(claimSyncAdapter).syncExchanges(account);
        order.verify(claimOrderBackfillService).backfill(account);
        order.verifyNoMoreInteractions();
    }

    @Test
    void sync_recordsClaimSyncCompleted() {
        withAccount();

        service.sync(7L);

        verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
    }

    @Test
    void sync_trackingFails_propagatesAndDoesNotRecord() {
        // 실패한 회차에 시각을 갱신하면 놓친 구간이 다음 창에서 빠져 영구히 사라진다.
        withAccount();
        willThrow(new IllegalStateException("쿠팡 추적 실패"))
                .given(coupangReturnSyncService).trackOpenClaims(account);

        assertThatThrownBy(() -> service.sync(7L)).isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder, never()).recordClaimSyncCompleted(anyLong());
        verify(claimOrderBackfillService, never()).backfill(any());
    }

    @Test
    void sync_exchangeFails_isIsolated() {
        withAccount();
        withExchangeAdapter();
        willThrow(new IllegalStateException("교환 조회 실패"))
                .given(claimSyncAdapter).syncExchanges(account);

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.skipped()).isFalse();
        verify(claimOrderBackfillService).backfill(account);   // 교환 실패가 백필을 건너뛰게 하지 않는다
        verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
    }

    @Test
    void sync_backfillFails_isIsolated() {
        withAccount();
        willThrow(new IllegalStateException("백필 실패"))
                .given(claimOrderBackfillService).backfill(account);

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.skipped()).isFalse();
        verify(syncStatusRecorder).recordClaimSyncCompleted(7L);
    }

    @Test
    void sync_channelAlreadyRunning_skipsWithoutCallingCoupang() {
        withAccount();
        accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, 7L);   // 주문 동기화가 쥔 상태

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.skipped()).isTrue();
        verify(coupangReturnSyncService, never()).syncCancels(any());
        verify(coupangReturnSyncService, never()).trackOpenClaims(any());
        verify(syncStatusRecorder, never()).recordClaimSyncCompleted(anyLong());
    }

    @Test
    void sync_unknownAccount_throwsNotFound() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sync_nonCoupangAccount_throwsBadRequest() {
        MarketplaceAccount naver = MarketplaceAccountFixture.coupangStubBuilder("A0002", null)
                .id(8L).platform(Platform.NAVER).tenantId(1L).build();
        given(marketplaceAccountRepository.findById(8L)).willReturn(Optional.of(naver));

        assertThatThrownBy(() -> service.sync(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는");

        verify(coupangReturnSyncService, never()).syncCancels(any());
    }

    @Test
    void sync_neverStampsOrderSyncStatus() {
        // 🔴 클레임만 돌고 lastSyncStatus 를 SUCCESS 로 바꾸면 주문이 실패한 채널이 정상으로 보인다.
        withAccount();

        service.sync(7L);

        verify(syncStatusRecorder, never()).recordSuccess(anyLong());
        verify(syncStatusRecorder, never()).recordPartial(anyLong(), anyString(), anyBoolean(), anyBoolean());
        verify(syncStatusRecorder, never()).recordFailure(anyLong(), any());
    }
}
