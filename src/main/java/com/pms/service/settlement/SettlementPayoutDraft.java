package com.pms.service.settlement;

import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 플랫폼 지급내역 응답 1건 = <b>지급 묶음 1건</b> (FEATURE_2609_30 / PLAN D5-2·D5-3).
 *
 * <p>🔴 <b>같은 인식월에 여러 건이 온다</b> — 주정산·월정산 외에 {@code ADDITIONAL}(추가정산)·
 * {@code RESERVE}(유보금)가 중간중간 들어온다. 응답 원소를 하나로 합치면 검증식이 영영 맞지 않고
 * 화면 건수가 실제 입금 건수와 달라진다. 어댑터는 원소 하나당 이 draft 하나를 만든다.
 *
 * <p>⚠️ {@link #recognitionFrom}/{@link #recognitionTo} 는 없을 수 있다 — 그때는
 * {@link SettlementPayoutUpserter} 가 인식월 전체(1일~말일)로 대체한다.
 *
 * @param adjustments 라인에 붙지 않는 배치 레벨 금액(차감·전주채무·보류해제…). 없으면 빈 리스트(D8)
 */
public record SettlementPayoutDraft(
        SettlementType settlementType,
        String revenueRecognitionMonth,
        LocalDate recognitionFrom,
        LocalDate recognitionTo,
        LocalDate settlementDate,
        LocalDate finalSettlementDate,
        BigDecimal totalSale,
        BigDecimal serviceFee,
        BigDecimal finalAmount,
        SettlementPayoutStatus status,
        List<SettlementAdjustmentDraft> adjustments) {

    public SettlementPayoutDraft {
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
    }
}
