package com.pms.service.settlement;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementType;
import com.pms.dto.response.SettlementPayoutSyncResponse;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.settlement.SettlementPayoutUpserter.PayoutUpsertResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 지급내역 적재 오케스트레이션 — 묶음 분리 유지 · 결정적 처리 순서 · 계정 격리 (PLAN D5-3·D5-5·D11).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementPayoutSyncServiceImplTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 8);

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private SettlementPayoutUpserter settlementPayoutUpserter;
    @Mock private SettlementSyncStatusWriter settlementSyncStatusWriter;
    @Mock private SettlementSource settlementSource;

    private final CoupangProperties coupangProperties = new CoupangProperties();
    private SettlementPayoutSyncServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
            .id(7L).platform(Platform.COUPANG).accountAlias("메인").build();

    @BeforeEach
    void setUp() {
        given(settlementSource.platform()).willReturn(Platform.COUPANG);
        service = new SettlementPayoutSyncServiceImpl(marketplaceAccountRepository, settlementPayoutUpserter,
                settlementSyncStatusWriter, coupangProperties, List.of(settlementSource));
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(settlementPayoutUpserter.upsert(any(), any())).willReturn(new PayoutUpsertResult(1, 3, 1));
    }

    @Test
    void payoutSplitsSameMonthIntoMultiplePayouts() {
        // 같은 인식월에 주정산 2 + 월정산 1 + 추가정산 1 = 묶음 4개. 🔴 하나로 합치지 않는다(D5-3).
        given(settlementSource.fetchPayouts(any(), eq(MONTH))).willReturn(List.of(
                draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4)),
                draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 11)),
                draft(SettlementType.MONTHLY, LocalDate.of(2026, 9, 15)),
                draft(SettlementType.ADDITIONAL, LocalDate.of(2026, 9, 8))));

        SettlementPayoutSyncResponse response = service.syncPayouts(7L, MONTH);

        verify(settlementPayoutUpserter, times(4)).upsert(any(), any());
        assertThat(response.payouts()).isEqualTo(4);
        assertThat(response.attributedLines()).isEqualTo(12);
    }

    @Test
    void attachmentOrderIsDeterministic() {
        // 응답 순서를 뒤집어도 처리 순서는 settlementDate ASC 로 고정된다 — 라인 귀속이 순서에 의존하므로
        // 같은 입력이면 언제나 같은 결과여야 한다(D5-5).
        given(settlementSource.fetchPayouts(any(), eq(MONTH))).willReturn(List.of(
                draft(SettlementType.ADDITIONAL, LocalDate.of(2026, 9, 8)),
                draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 11)),
                draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4))));

        service.syncPayouts(7L, MONTH);

        ArgumentCaptor<SettlementPayoutDraft> processed = ArgumentCaptor.forClass(SettlementPayoutDraft.class);
        verify(settlementPayoutUpserter, times(3)).upsert(any(), processed.capture());
        assertThat(processed.getAllValues()).extracting(SettlementPayoutDraft::settlementDate)
                .containsExactly(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 11));
    }

    @Test
    void firstRunBackfillsSeveralMonthsAndLaterRunsReadTwo() {
        coupangProperties.setPayoutBackfillMonths(3);
        given(settlementSource.fetchPayouts(any(), any())).willReturn(List.of());

        service.syncPayouts(7L, null);
        verify(settlementSource, times(3)).fetchPayouts(any(), any());

        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(
                account.toBuilder().lastPayoutSyncAt(java.time.LocalDateTime.now().minusDays(7)).build()));
        service.syncPayouts(7L, null);
        verify(settlementSource, times(5)).fetchPayouts(any(), any());   // 3 + 2(당월·직전월)
    }

    @Test
    void futureMonthIsRejected() {
        assertThatThrownBy(() -> service.syncPayouts(7L, YearMonth.now().plusMonths(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountFailureIsIsolatedAndAnchorNotWritten() {
        given(settlementSource.fetchPayouts(any(), any()))
                .willThrow(new IllegalStateException("쿠팡 500"));

        SettlementPayoutSyncResponse response = service.syncPayouts(7L, MONTH);

        assertThat(response.failedAccounts()).hasSize(1);
        assertThat(response.payouts()).isZero();
        verify(settlementSyncStatusWriter, times(0)).writePayoutSyncAt(any());
    }

    private static SettlementPayoutDraft draft(SettlementType type, LocalDate settlementDate) {
        return new SettlementPayoutDraft(type, "2026-08", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31), settlementDate, null, new BigDecimal("1000000"),
                new BigDecimal("100000"), new BigDecimal("900000"), SettlementPayoutStatus.PAID, List.of());
    }
}
