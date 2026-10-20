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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ClaimSyncService — the claims-only round (FEATURE_2609_70 / D14 · D15 · D15-1): the matching adapter runs under the
 * lock and the account's tenant (FEATURE_2610_23 / D12 · D21). The rounds themselves are in
 * {@code ClaimSyncAdapterClaimsOnlyTest}.
 *
 * <p>🔴 The lock is a real instance, not a mock (the same way as 2609_48 Step 7-2) — a test takes {@code tryAcquire}
 * first to make the "already running" state. No threads.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSyncServiceTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ClaimSyncAdapter coupangAdapter;
    @Mock private ClaimSyncAdapter elevenstAdapter;

    private AccountSyncLock accountSyncLock;
    private ClaimSyncServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A0001", null)
            .id(7L).platform(Platform.COUPANG).tenantId(1L).build();

    @BeforeEach
    void setUp() {
        accountSyncLock = new AccountSyncLock(new CoupangProperties(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
        service = new ClaimSyncServiceImpl(marketplaceAccountRepository, accountSyncLock,
                List.of(coupangAdapter, elevenstAdapter));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();   // 테스트가 세팅한 요청 테넌트를 다음 테스트로 흘리지 않는다
    }

    @Test
    void sync_runsTheMatchingAdapterUnderTheAccountTenant() {
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        AtomicReference<Long> tenantSeen = new AtomicReference<>();
        willAnswer(invocation -> {
            tenantSeen.set(TenantContext.get());
            return null;
        }).given(coupangAdapter).syncClaimsOnly(account);

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.accountId()).isEqualTo(7L);
        assertThat(response.skipped()).isFalse();
        assertThat(response.syncedAt()).isNotNull();
        assertThat(tenantSeen.get()).isEqualTo(1L);
        verify(elevenstAdapter, never()).syncClaimsOnly(any());
    }

    @Test
    void sync_roundFails_propagates() {
        // D15-1 — a 200 after a failed round would tell the screen "just became current".
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        willThrow(new IllegalStateException("쿠팡 추적 실패")).given(coupangAdapter).syncClaimsOnly(account);

        assertThatThrownBy(() -> service.sync(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sync_channelAlreadyRunning_skipsWithoutCallingTheAdapter() {
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, 7L);   // 주문 동기화가 쥔 상태

        ClaimSyncResponse response = service.sync(7L);

        assertThat(response.skipped()).isTrue();
        verify(coupangAdapter, never()).syncClaimsOnly(any());
    }

    @Test
    void sync_unknownAccount_throwsNotFound() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sync_platformWithoutAdapter_throwsBadRequest() {
        // FEATURE_2610_23 / D14 — the same 400 and wording as before the adapters owned the round.
        MarketplaceAccount naver = MarketplaceAccountFixture.coupangStubBuilder("A0002", null)
                .id(8L).platform(Platform.NAVER).tenantId(1L).build();
        given(marketplaceAccountRepository.findById(8L)).willReturn(Optional.of(naver));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        given(elevenstAdapter.platform()).willReturn(Platform.ELEVENST);

        assertThatThrownBy(() -> service.sync(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("클레임 조회를 지원하지 않는 채널입니다: NAVER");

        verify(coupangAdapter, never()).syncClaimsOnly(any());
        verify(elevenstAdapter, never()).syncClaimsOnly(any());
    }

    /** FEATURE_2610_17 / D15: an 11st channel runs only the 11st adapter. */
    @Test
    void elevenst_runsTheElevenstAdapterOnly() {
        MarketplaceAccount eleven = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).tenantId(1L).build();
        given(marketplaceAccountRepository.findById(9L)).willReturn(Optional.of(eleven));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        given(elevenstAdapter.platform()).willReturn(Platform.ELEVENST);

        service.sync(9L);

        verify(elevenstAdapter).syncClaimsOnly(eleven);
        verify(coupangAdapter, never()).syncClaimsOnly(any());
    }
}
