package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 지급 묶음 적재 — 라인 귀속 범위 · 추가정산 제외 · 조정 멱등 (PLAN D5-4·D5-5·D8).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementPayoutUpserterTest {

    private static final String MONTH = "2026-08";

    @Mock private SettlementPayoutRepository settlementPayoutRepository;
    @Mock private SettlementAdjustmentRepository settlementAdjustmentRepository;
    @Mock private SettlementLineRepository settlementLineRepository;

    private SettlementPayoutUpserter upserter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
            .id(7L).platform(Platform.COUPANG).build();

    @BeforeEach
    void setUp() {
        upserter = new SettlementPayoutUpserter(settlementPayoutRepository, settlementAdjustmentRepository,
                settlementLineRepository, new SettlementReconciler(BigDecimal.ONE));

        given(settlementPayoutRepository
                .findByMarketplaceAccount_IdAndRevenueRecognitionMonthAndSettlementTypeAndSettlementDate(
                        anyLong(), any(), any(), any()))
                .willReturn(Optional.empty());
        given(settlementPayoutRepository.save(any())).willAnswer(invocation -> {
            SettlementPayout payout = invocation.getArgument(0);
            return payout.getId() == null ? payout.toBuilder().id(100L).build() : payout;
        });
        given(settlementAdjustmentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(settlementAdjustmentRepository.findBySettlementPayout_Id(anyLong())).willReturn(List.of());
        given(settlementAdjustmentRepository.findBySettlementPayout_IdAndAdjustmentType(anyLong(), any()))
                .willReturn(Optional.empty());
        given(settlementLineRepository.findBySettlementPayout_IdOrderByRecognitionDateAscIdAsc(anyLong()))
                .willReturn(List.of());
        given(settlementLineRepository.findAttributable(anyLong(), any(), any(), any()))
                .willReturn(List.of());
    }

    @Test
    void attachesNullPayoutLinesWithinRecognitionRange() {
        SettlementLine free = line(1L, null);
        SettlementLine takenByAnother = line(2L, SettlementPayout.builder().id(55L).build());
        given(settlementLineRepository.findAttributable(anyLong(), any(), any(), any()))
                .willReturn(List.of(free, takenByAnother));

        upserter.upsert(account, draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4), "1000000"));

        ArgumentCaptor<List<SettlementLine>> saved = ArgumentCaptor.forClass(List.class);
        verify(settlementLineRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getId()).isEqualTo(1L);
        assertThat(saved.getValue().get(0).getSettlementPayout().getId()).isEqualTo(100L);
    }

    @Test
    void additionalPayoutTakesNoLines() {
        // 🔴 추가정산이 주정산과 같은 인식 구간을 덮어도 라인을 가져가면 안 된다(= 매출 이중계상).
        //    이 테스트가 그 유일한 보증이다.
        SettlementPayoutUpserter.PayoutUpsertResult result =
                upserter.upsert(account, draft(SettlementType.ADDITIONAL, LocalDate.of(2026, 9, 4), "12000"));

        verify(settlementLineRepository, never()).findAttributable(anyLong(), any(), any(), any());
        verify(settlementLineRepository, never()).saveAll(any());
        assertThat(result.attributedLines()).isZero();
        assertThat(lastSavedPayout().getReconStatus()).isEqualTo(SettlementReconStatus.AMOUNT_ONLY);
    }

    @Test
    void payoutWithNoLinesIsReconciledFromAdjustmentsAlone() {
        // 라인 0건 묶음은 정상이다(D5-4) — 차감만으로 검증식이 성립한다. 숨기거나 예외로 다루지 않는다.
        given(settlementAdjustmentRepository.findBySettlementPayout_Id(anyLong()))
                .willReturn(List.of(SettlementAdjustment.builder()
                        .adjustmentType(SettlementAdjustmentType.DEDUCTION)
                        .amount(new BigDecimal("85000")).build()));

        SettlementPayoutUpserter.PayoutUpsertResult result = upserter.upsert(account,
                draft(SettlementType.MONTHLY, LocalDate.of(2026, 9, 4), "-85000",
                        new SettlementAdjustmentDraft(SettlementAdjustmentType.DEDUCTION,
                                new BigDecimal("85000"), null)));

        assertThat(result.payouts()).isEqualTo(1);
        assertThat(result.adjustments()).isEqualTo(1);
        assertThat(lastSavedPayout().getReconStatus()).isEqualTo(SettlementReconStatus.RECONCILED);
    }

    @Test
    void adjustmentUpsertIsIdempotent() {
        SettlementPayoutDraft first = draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4), "1000000",
                new SettlementAdjustmentDraft(SettlementAdjustmentType.DEDUCTION, new BigDecimal("85000"), null));
        upserter.upsert(account, first);

        SettlementAdjustment existing = SettlementAdjustment.builder()
                .id(31L).adjustmentType(SettlementAdjustmentType.DEDUCTION)
                .amount(new BigDecimal("85000")).build();
        given(settlementAdjustmentRepository.findBySettlementPayout_IdAndAdjustmentType(anyLong(), any()))
                .willReturn(Optional.of(existing));
        upserter.upsert(account, draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4), "1000000",
                new SettlementAdjustmentDraft(SettlementAdjustmentType.DEDUCTION, new BigDecimal("90000"), null)));

        ArgumentCaptor<SettlementAdjustment> saved = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(settlementAdjustmentRepository, times(2)).save(saved.capture());
        // 2회차는 새 행이 아니라 같은 행의 UPDATE 다 — id 가 유지되고 금액만 최신값이다.
        assertThat(saved.getAllValues().get(0).getId()).isNull();
        assertThat(saved.getAllValues().get(1).getId()).isEqualTo(31L);
        assertThat(saved.getAllValues().get(1).getAmount()).isEqualByComparingTo("90000");
    }

    /**
     * 🔴 이번 응답에 없는 조정은 <b>지운다</b>. 조정 행은 전부 지급내역 응답에서 나오므로 "이번에 안 왔다"
     * 는 "더는 없다" 는 뜻이다 — 남겨 두면 낡은 값이 영원히 화면에 붙어 있는다(파서를 고친 뒤에도 예전
     * {@code OTHER} 행이 재동기화로 사라지지 않아 실제로 그랬다).
     */
    @Test
    void adjustmentsMissingFromTheResponseAreRemoved() {
        SettlementAdjustment stale = SettlementAdjustment.builder()
                .id(77L).adjustmentType(SettlementAdjustmentType.OTHER)
                .amount(new BigDecimal("6903562")).note("미매핑 필드: …").build();
        SettlementAdjustment kept = SettlementAdjustment.builder()
                .id(78L).adjustmentType(SettlementAdjustmentType.DEDUCTION)
                .amount(new BigDecimal("85000")).build();
        given(settlementAdjustmentRepository.findBySettlementPayout_Id(anyLong()))
                .willReturn(List.of(stale, kept));

        upserter.upsert(account, draft(SettlementType.WEEKLY, LocalDate.of(2026, 9, 4), "1000000",
                new SettlementAdjustmentDraft(SettlementAdjustmentType.DEDUCTION,
                        new BigDecimal("85000"), null)));

        // 이번 응답이 준 DEDUCTION 은 남고, 사라진 OTHER 만 지워진다.
        verify(settlementAdjustmentRepository).delete(stale);
        verify(settlementAdjustmentRepository, never()).delete(kept);
    }

    @Test
    void missingRecognitionRangeFallsBackToWholeMonth() {
        upserter.upsert(account, new SettlementPayoutDraft(SettlementType.MONTHLY, MONTH, null, null,
                LocalDate.of(2026, 9, 4), null, new BigDecimal("1000000"), new BigDecimal("100000"),
                new BigDecimal("900000"), SettlementPayoutStatus.PAID, List.of()));

        verify(settlementLineRepository).findAttributable(7L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 100L);
    }

    @Test
    void missingRecognitionMonthIsRejected() {
        SettlementPayoutDraft broken = new SettlementPayoutDraft(SettlementType.WEEKLY, null, null, null,
                LocalDate.of(2026, 9, 4), null, null, null, null, SettlementPayoutStatus.PAID, List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upserter.upsert(account, broken))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SettlementPayout lastSavedPayout() {
        ArgumentCaptor<SettlementPayout> saved = ArgumentCaptor.forClass(SettlementPayout.class);
        verify(settlementPayoutRepository, times(2)).save(saved.capture());
        return saved.getAllValues().get(saved.getAllValues().size() - 1);
    }

    private static SettlementLine line(Long id, SettlementPayout payout) {
        return SettlementLine.builder().id(id).saleType(SaleType.SALE)
                .settlementAmount(new BigDecimal("1000"))
                .recognitionDate(LocalDate.of(2026, 8, 10))
                .settlementPayout(payout).build();
    }

    private static SettlementPayoutDraft draft(SettlementType type, LocalDate settlementDate,
                                               String finalAmount, SettlementAdjustmentDraft... adjustments) {
        return new SettlementPayoutDraft(type, MONTH, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                settlementDate, null, new BigDecimal("1200000"), new BigDecimal("120000"),
                new BigDecimal(finalAmount), SettlementPayoutStatus.PAID, List.of(adjustments));
    }
}
