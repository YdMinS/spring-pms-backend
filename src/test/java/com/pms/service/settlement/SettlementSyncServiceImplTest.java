package com.pms.service.settlement;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.SettlementSyncResponse;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.settlement.SettlementLineUpserter.UpsertResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 정산 적재 오케스트레이션 — 최소 간격 가드 · 부분 실패 시 앵커 미갱신 · 기간 백필 (PLAN D11).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementSyncServiceImplTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private SettlementLineUpserter settlementLineUpserter;
    @Mock private SettlementSyncStatusWriter settlementSyncStatusWriter;
    @Mock private SettlementSource settlementSource;

    private final CoupangProperties coupangProperties = new CoupangProperties();
    private SettlementSyncServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
            .id(7L).platform(Platform.COUPANG).accountAlias("메인").build();

    @BeforeEach
    void setUp() {
        given(settlementSource.platform()).willReturn(Platform.COUPANG);
        service = new SettlementSyncServiceImpl(marketplaceAccountRepository, settlementLineUpserter,
                settlementSyncStatusWriter, coupangProperties, List.of(settlementSource), 10);
        given(settlementLineUpserter.upsertPage(any(), any(), any()))
                .willReturn(new UpsertResult(1, 1, 0, 0));
    }

    @Test
    void manualSyncSkippedWithinMinInterval() {
        MarketplaceAccount recent = account.toBuilder()
                .lastSettlementSyncAt(LocalDateTime.now().minusMinutes(3)).build();
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(recent));

        SettlementSyncResponse response = service.sync(null, null);

        verify(settlementSource, never()).fetchRevenue(any(), any(), any(), any());
        assertThat(response.skipped()).isTrue();
        assertThat(response.nextAvailableAt()).isNotNull();
        assertThat(response.lines()).isZero();
    }

    @Test
    void manualSyncRunsWhenIntervalPassed() {
        MarketplaceAccount stale = account.toBuilder()
                .lastSettlementSyncAt(LocalDateTime.now().minusHours(3)).build();
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(stale));
        givenOnePage();

        SettlementSyncResponse response = service.sync(null, null);

        verify(settlementSource).fetchRevenue(any(), any(), any(), any());
        verify(settlementSyncStatusWriter).writeRevenueSyncAt(7L);
        assertThat(response.skipped()).isFalse();
        assertThat(response.lines()).isEqualTo(1);
        assertThat(response.matched()).isEqualTo(1);
        assertThat(response.failedAccounts()).isEmpty();
    }

    @Test
    void partialFailureLeavesLastSyncAtUntouched() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        // 1페이지는 적재되고 2페이지에서 실패한다.
        willAnswer(invocation -> {
            consumer(invocation.getArgument(3)).accept(List.of(draft()));
            throw new IllegalStateException("쿠팡 응답 오류");
        }).given(settlementSource).fetchRevenue(any(), any(), any(), any());

        SettlementSyncResponse response = service.sync(null, null);

        verify(settlementSyncStatusWriter, never()).writeRevenueSyncAt(anyLong());
        assertThat(response.failedAccounts()).hasSize(1);
        assertThat(response.failedAccounts().get(0)).contains("메인");
        assertThat(response.skipped()).isFalse();
    }

    @Test
    void oneFailedAccountDoesNotStopTheRest() {
        MarketplaceAccount second = account.toBuilder().id(8L).accountAlias("서브").build();
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account, second));
        willThrow(new IllegalStateException("boom"))
                .willAnswer(invocation -> {
                    consumer(invocation.getArgument(3)).accept(List.of(draft()));
                    return null;
                })
                .given(settlementSource).fetchRevenue(any(), any(), any(), any());

        SettlementSyncResponse response = service.sync(null, null);

        assertThat(response.accounts()).isEqualTo(2);
        assertThat(response.failedAccounts()).hasSize(1);
        assertThat(response.lines()).isEqualTo(1);
        verify(settlementSyncStatusWriter).writeRevenueSyncAt(8L);
        verify(settlementSyncStatusWriter, never()).writeRevenueSyncAt(7L);
    }

    @Test
    void periodBackfillIgnoresGuardAndDoesNotTouchAnchor() {
        MarketplaceAccount recent = account.toBuilder()
                .lastSettlementSyncAt(LocalDateTime.now().minusMinutes(1)).build();
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(recent));
        givenOnePage();

        SettlementSyncResponse response = service.syncPeriod(7L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        verify(settlementSource).fetchRevenue(any(), any(), any(), any());
        // 과거 구간 백필이 "최신까지 읽었다"가 되면 delta 창에 구멍이 생긴다.
        verify(settlementSyncStatusWriter, never()).writeRevenueSyncAt(anyLong());
        assertThat(response.skipped()).isFalse();
        assertThat(response.lines()).isEqualTo(1);
    }

    @Test
    void periodBackfillRejectsInvertedRange() {
        assertThatThrownBy(() -> service.syncPeriod(7L,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(settlementSource, never()).fetchRevenue(any(), any(), any(), any());
    }

    private void givenOnePage() {
        willAnswer(invocation -> {
            consumer(invocation.getArgument(3)).accept(List.of(draft()));
            return null;
        }).given(settlementSource).fetchRevenue(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static Consumer<List<SettlementLineDraft>> consumer(Object argument) {
        return (Consumer<List<SettlementLineDraft>>) argument;
    }

    private static SettlementLineDraft draft() {
        return new SettlementLineDraft("O1", "V10", com.pms.domain.SaleType.SALE,
                LocalDate.of(2026, 8, 5), null, null, null, 1,
                null, null, null, null, null, null, null,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
    }
}
