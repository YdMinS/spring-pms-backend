package com.pms.service.settlement;

import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.ReconLineView;
import com.pms.dto.response.ReconReportResponse;
import com.pms.dto.response.SettlementPayoutDetailResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 대사 <b>조회</b> 전용 서비스 (FEATURE_2609_30 / 02 · PLAN D9·D12·D13·D17).
 *
 * <p><b>필수 규칙</b>: 이 인터페이스는 <b>읽기만</b> 한다. 대사 결과({@code recon_status})는 적재 시점에
 * {@link SettlementPayoutUpserter} 가 쓰고, 여기서는 저장된 원장을 다시 계산해 보여줄 뿐이다 —
 * 조회가 원장을 고치면 "언제 값이 바뀌었나"에 답할 수 없다(불변 원장).
 *
 * <p>⚠️ 금액 계산은 전부 {@link SettlementReconciler}·{@link SettlementDiffAnalyzer} 에 위임한다.
 * 여기서 다시 더하지 말 것 — 화면과 저장값이 다른 답을 내는 순간 대사가 의미를 잃는다.
 */
public interface SettlementReconciliationService {

    /** 지급 묶음 목록. 파라미터가 null 이면 그 조건은 적용하지 않는다. */
    List<PayoutSummary> payouts(Long sellerId, Long accountId, LocalDate from, LocalDate to);

    /** 묶음 1건 + 조정 행 + 검증식 요약 금액. */
    SettlementPayoutDetailResponse payout(Long payoutId);

    /**
     * 묶음의 라인 목록.
     *
     * @param label     원인 라벨 필터(null = 전체)
     * @param unmatched true 면 미분류 라인만
     */
    List<ReconLineView> lines(Long payoutId, String label, Boolean unmatched);

    /** 차이 리포트 2단 (D12). */
    ReconReportResponse report(Long payoutId);

    /** 리포트 ①의 라인 목록을 그대로 xlsx 로. */
    byte[] export(Long payoutId);
}
