package com.pms.service.sales;

import com.pms.domain.FirstMonthState;
import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.Platform;
import com.pms.domain.PlatformFixedCost;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.SettlementLineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 11st server-fee first month (M0) — FEATURE_2610_20 / D45 ② ~ ⑦.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstFirstMonthServiceTest {

    @Mock private MarketplaceAccountFixedCostRepository fixedCostRepository;
    @Mock private SettlementLineRepository settlementLineRepository;

    @InjectMocks private ElevenstFirstMonthService service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY")
            .id(9L).accountAlias("11번가").build();

    @Test
    void startJudgingWritesFirstMonthFromEarliestLine() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(link(null, null, null)));
        given(settlementLineRepository.findEarliestRecognitionDate(9L)).willReturn(LocalDate.of(2026, 10, 14));

        List<YearMonth> months = service.startJudging(account);

        assertThat(months).containsExactly(YearMonth.of(2026, 10));
        ArgumentCaptor<MarketplaceAccountFixedCost> saved = ArgumentCaptor.forClass(MarketplaceAccountFixedCost.class);
        verify(fixedCostRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstMonth()).isEqualTo("2026-10");
        assertThat(saved.getValue().getFirstMonthState()).isNull();
    }

    @Test
    void startJudgingWaitsWhileTheAccountHasNoLine() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(link(null, null, null)));
        given(settlementLineRepository.findEarliestRecognitionDate(9L)).willReturn(null);

        assertThat(service.startJudging(account)).isEmpty();
        verify(fixedCostRepository, never()).save(any());
    }

    /** D45 ⑤: M0 written by an earlier run is reused — never recomputed. */
    @Test
    void startJudgingKeepsAnExistingFirstMonth() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(link("2026-10", null, null)));

        assertThat(service.startJudging(account)).containsExactly(YearMonth.of(2026, 10));
        verify(settlementLineRepository, never()).findEarliestRecognitionDate(any());
        verify(fixedCostRepository, never()).save(any());
    }

    @Test
    void finishJudgingSetsAutoOrAsking() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(link("2026-10", null, null)));

        service.finishJudging(account, YearMonth.of(2026, 10), false);

        ArgumentCaptor<MarketplaceAccountFixedCost> saved = ArgumentCaptor.forClass(MarketplaceAccountFixedCost.class);
        verify(fixedCostRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstMonthState()).isEqualTo(FirstMonthState.ASKING);
    }

    /** D45 ③: the previous month's lines arrived while asking → AUTO, the question goes away. */
    @Test
    void resolveAskingTurnsAutoWhenPreviousMonthHasLines() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L))
                .willReturn(List.of(link("2026-10", FirstMonthState.ASKING, null)));
        given(settlementLineRepository.countByMarketplaceAccount_IdAndRecognitionDateBetween(
                9L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).willReturn(3L);

        service.resolveAsking(account);

        ArgumentCaptor<MarketplaceAccountFixedCost> saved = ArgumentCaptor.forClass(MarketplaceAccountFixedCost.class);
        verify(fixedCostRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstMonthState()).isEqualTo(FirstMonthState.AUTO);
    }

    /** D45 ④: an answered link is never touched again. */
    @Test
    void answeredLinkIsLeftAlone() {
        given(fixedCostRepository.findByMarketplaceAccount_Id(9L))
                .willReturn(List.of(link("2026-10", FirstMonthState.ANSWERED, true)));

        service.resolveAsking(account);
        assertThat(service.startJudging(account)).isEmpty();
        service.finishJudging(account, YearMonth.of(2026, 10), true);

        verify(fixedCostRepository, never()).save(any());
    }

    @Test
    void answerRecordsTheAnswer() {
        given(fixedCostRepository.findById(5L)).willReturn(Optional.of(link("2026-10", FirstMonthState.ASKING, null)));

        service.answer(5L, false);

        ArgumentCaptor<MarketplaceAccountFixedCost> saved = ArgumentCaptor.forClass(MarketplaceAccountFixedCost.class);
        verify(fixedCostRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstMonthState()).isEqualTo(FirstMonthState.ANSWERED);
        assertThat(saved.getValue().getFirstMonthCharged()).isFalse();
    }

    @Test
    void answerRejectsALinkThatIsNotAsking() {
        given(fixedCostRepository.findById(5L)).willReturn(Optional.of(link("2026-10", FirstMonthState.AUTO, null)));

        assertThatThrownBy(() -> service.answer(5L, true)).isInstanceOf(IllegalArgumentException.class);
    }

    private MarketplaceAccountFixedCost link(String firstMonth, FirstMonthState state, Boolean charged) {
        return MarketplaceAccountFixedCost.builder()
                .id(5L)
                .marketplaceAccount(account)
                .platformFixedCost(PlatformFixedCost.builder()
                        .platform(Platform.ELEVENST).name("11번가 서버이용료")
                        .amount(new BigDecimal("77000")).thresholdAmount(new BigDecimal("5000000"))
                        .active(true).build())
                .chargeMode(FixedCostChargeMode.AUTO)
                .firstMonth(firstMonth)
                .firstMonthState(state)
                .firstMonthCharged(charged)
                .build();
    }
}
