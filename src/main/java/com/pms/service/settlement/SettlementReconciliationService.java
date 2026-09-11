package com.pms.service.settlement;

import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.SaleMonthSettlement;
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

    /** 지급 묶음 목록 — <b>지급일</b> 축. 파라미터가 null 이면 그 조건은 적용하지 않는다. */
    List<PayoutSummary> payouts(Long sellerId, Long accountId, LocalDate from, LocalDate to);

    /**
     * 지급 묶음 목록 — <b>매출인식월</b> 축 (FEATURE_2609_34). 매출 화면이 "이 기간 매출에 대한 정산"을
     * 나열할 때 쓴다.
     *
     * <p>🔴 {@link #payouts} 와 축이 다르다(지급일 vs 인식월). 매출 화면의 기간은 판매일 축이라 지급일로
     * 자르면 8월에 판 것을 9월에 받는 건이 통째로 빠진다. 두 메서드를 하나로 합치지 말 것.
     *
     * <p>응답은 인식월 내림차순 → 같은 달 안에서 지급일 오름차순이다. 여러 달을 조회하면 달마다 여러 건이
     * 나오므로 <b>월 단위 묶음은 화면이</b> 만든다(서버는 정렬만 보장한다).
     *
     * @param from 판매일 기준 시작일 — 이 날짜가 속한 <b>달</b>부터
     * @param to   판매일 기준 종료일 — 이 날짜가 속한 <b>달</b>까지
     */
    List<PayoutSummary> payoutsByRecognitionMonth(Long sellerId, Long accountId, LocalDate from, LocalDate to);

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

    /**
     * 판매월 기준 정산 — "그 달 판매가 언제 얼마로 정산됐나" (FEATURE_2609_34).
     *
     * <p>🔴 {@link #payoutsByRecognitionMonth} 와 <b>축이 반대</b>다. 저쪽은 정산 건에서 판매를 내려다보고,
     * 이쪽은 판매에서 정산 시점을 올려다본다. 한 달 판매가 여러 번에 나눠 정산되는 경우는 이쪽에서만 보인다.
     *
     * <p>⚠️ 아직 어느 지급에도 붙지 않은 판매는 정산 시점이 없어 빠진다 — 화면이 따로 말한다.
     */
    List<SaleMonthSettlement> bySaleMonth(Long accountId, LocalDate from, LocalDate to);
}