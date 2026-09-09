package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementReconStatus;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SettlementPayoutDraft} → {@code settlement_payout} 멱등 upsert + 조정 행 + 라인 귀속 + 대사
 * (FEATURE_2609_30 / 02 · PLAN D5-2·D5-3·D5-4·D5-5·D8·D9).
 *
 * <p>⚠️ {@code REQUIRES_NEW} — 호출자({@link SettlementPayoutSyncServiceImpl})는 외부 HTTP 루프라 합류할
 * 트랜잭션이 없다. 묶음 1건 = 트랜잭션 1건이라 3번째 묶음에서 실패해도 앞의 두 건은 남는다
 * ({@link SettlementLineUpserter} 와 같은 자세).
 *
 * <p>🔴 <b>라인 귀속은 WEEKLY·MONTHLY 에만 한다</b>(D5-5). 지급내역은 월 집계라 "어느 주문이 이 지급에
 * 들어갔는지"를 쿠팡이 알려주지 않는다. 우리 귀속은 인식일 구간 기반 <b>추정</b>이고 추가정산은 주정산과
 * 같은 구간을 덮으므로, 양쪽이 같은 라인을 가져가면 <b>매출이 두 번 계상</b>되고 처리 순서에 따라 결과가
 * 달라진다(비결정적). 호출자가 {@code settlementDate ASC} 로 고정해 넘긴다.
 *
 * <p>🔴 <b>delete-insert 금지</b>. 조정은 {@code (묶음, 유형)} 으로 upsert 하고, 이미 <b>다른</b> 묶음에
 * 귀속된 라인은 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPayoutUpserter {

    private final SettlementPayoutRepository settlementPayoutRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementReconciler settlementReconciler;

    /**
     * 지급 묶음 1건을 적재하고 그 자리에서 대사까지 끝낸다.
     *
     * @throws IllegalArgumentException 유일키를 만들 수 없을 때(인식월·지급일이 둘 다 없음) → 400
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayoutUpsertResult upsert(MarketplaceAccount account, SettlementPayoutDraft draft) {
        String month = draft.revenueRecognitionMonth();
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("지급내역에 매출인식월이 없어 지급 묶음을 식별할 수 없습니다.");
        }

        SettlementPayout payout = savePayout(account, draft, month);
        int adjustments = saveAdjustments(payout, draft);
        int attached = attachLines(account, payout, month);

        List<SettlementLine> lines =
                settlementLineRepository.findBySettlementPayout_IdOrderByRecognitionDateAscIdAsc(payout.getId());
        List<SettlementAdjustment> saved = settlementAdjustmentRepository.findBySettlementPayout_Id(payout.getId());

        SettlementReconStatus status = settlementReconciler.evaluate(payout, lines, saved);
        settlementPayoutRepository.save(payout.toBuilder().reconStatus(status).build());

        // 🔴 라인 0건은 정상이다(D5-4) — 유보금 해제·채무 상환·광고비 정산은 판매 라인이 없다.
        //    0건이 이상 신호인 경우는 WEEKLY/MONTHLY 인데도 0건일 때뿐이라 그때만 눈에 띄게 남긴다.
        log.info("settlement payout upserted: account={} month={} type={} lines={} attached={} "
                        + "adjustments={} recon={}",
                account.getId(), month, draft.settlementType(), lines.size(), attached, adjustments, status);
        return new PayoutUpsertResult(1, attached, adjustments);
    }

    private SettlementPayout savePayout(MarketplaceAccount account, SettlementPayoutDraft draft, String month) {
        Optional<SettlementPayout> found = settlementPayoutRepository
                .findByMarketplaceAccount_IdAndRevenueRecognitionMonthAndSettlementTypeAndSettlementDate(
                        account.getId(), month, draft.settlementType(), draft.settlementDate());

        // 정정은 UPDATE 로만 반영한다 — 지우고 다시 넣으면 이미 귀속된 라인의 FK 가 끊긴다.
        SettlementPayout base = found.map(SettlementPayout::toBuilder)
                .orElseGet(() -> SettlementPayout.builder()
                        .marketplaceAccount(account)
                        .revenueRecognitionMonth(month)
                        .settlementType(draft.settlementType())
                        .settlementDate(draft.settlementDate())
                        .reconStatus(SettlementReconStatus.PENDING))
                .recognitionFrom(draft.recognitionFrom())
                .recognitionTo(draft.recognitionTo())
                .finalSettlementDate(draft.finalSettlementDate())
                .totalSale(draft.totalSale())
                .serviceFee(draft.serviceFee())
                .finalAmount(draft.finalAmount())
                .status(draft.status())
                .build();
        return settlementPayoutRepository.save(base);
    }

    /** 조정 행 upsert (D8). 같은 묶음을 두 번 적재해도 행 수는 그대로고 금액만 최신값이 된다. */
    private int saveAdjustments(SettlementPayout payout, SettlementPayoutDraft draft) {
        int count = 0;
        for (SettlementAdjustmentDraft adjustment : draft.adjustments()) {
            if (adjustment.amount() == null) {
                continue;
            }
            SettlementAdjustment existing = settlementAdjustmentRepository
                    .findBySettlementPayout_IdAndAdjustmentType(payout.getId(), adjustment.type())
                    .orElse(null);
            SettlementAdjustment row = existing == null
                    ? SettlementAdjustment.builder()
                            .settlementPayout(payout)
                            .adjustmentType(adjustment.type())
                            .amount(adjustment.amount())
                            .note(adjustment.note())
                            .build()
                    : existing.toBuilder().amount(adjustment.amount()).note(adjustment.note()).build();
            settlementAdjustmentRepository.save(row);
            count++;
        }
        return count;
    }

    /**
     * 인식일 구간의 <b>주인 없는</b> 라인을 이 묶음에 귀속시킨다 (D5-5).
     *
     * <p>🔴 {@code ADDITIONAL}·{@code RESERVE} 는 라인을 가져가지 않는다 — 주정산과 같은 구간을 덮기 때문이다.
     * ⚠️ 인식 구간이 응답에 없으면 인식월 전체(1일~말일)로 대체한다.
     */
    private int attachLines(MarketplaceAccount account, SettlementPayout payout, String month) {
        if (!settlementReconciler.isReconcilable(payout.getSettlementType())) {
            return 0;
        }
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate from = payout.getRecognitionFrom() != null ? payout.getRecognitionFrom() : yearMonth.atDay(1);
        LocalDate to = payout.getRecognitionTo() != null ? payout.getRecognitionTo() : yearMonth.atEndOfMonth();

        List<SettlementLine> candidates =
                settlementLineRepository.findAttributable(account.getId(), from, to, payout.getId());
        List<SettlementLine> attached = new ArrayList<>();
        for (SettlementLine line : candidates) {
            if (line.getSettlementPayout() == null) {
                attached.add(line.toBuilder().settlementPayout(payout).build());
            }
        }
        if (!attached.isEmpty()) {
            settlementLineRepository.saveAll(attached);
        }
        return attached.size();
    }

    /** 묶음 1건 적재의 집계. */
    public record PayoutUpsertResult(int payouts, int attributedLines, int adjustments) {

        public static PayoutUpsertResult empty() {
            return new PayoutUpsertResult(0, 0, 0);
        }

        public PayoutUpsertResult plus(PayoutUpsertResult other) {
            return new PayoutUpsertResult(payouts + other.payouts,
                    attributedLines + other.attributedLines, adjustments + other.adjustments);
        }
    }
}
