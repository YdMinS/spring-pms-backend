package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CommissionApplyRequest;
import com.pms.dto.response.CommissionApplyResponse;
import com.pms.dto.response.CommissionSuggestionResponse;
import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.SaleMonthSettlement;
import com.pms.dto.response.ReconLineView;
import com.pms.dto.response.ReconReportResponse;
import com.pms.dto.response.SettlementPayoutDetailResponse;
import com.pms.dto.response.SettlementPayoutSyncResponse;
import com.pms.dto.response.SettlementSyncResponse;
import com.pms.dto.response.SettlementSyncTargetResponse;
import com.pms.service.settlement.CommissionFeedbackService;
import com.pms.service.settlement.SettlementPayoutSyncService;
import com.pms.service.settlement.SettlementReconciliationService;
import com.pms.service.settlement.SettlementSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 정산 원장 동기화 트리거 API — <b>ADMIN 전용</b> (FEATURE_2609_30 / PLAN D11 · D17).
 *
 * <p>정산·손익은 경영 데이터라 구매목록·재고와 같은 등급이다({@code PurchaseListController} 미러).
 *
 * <p>지급 묶음 조회·차이 리포트·엑셀은 FEATURE_2609_30 / 02 가 더한 것이다. 조회는 전부 로컬 DB 라
 * 마켓을 부르지 않는다 — 화면 진입이 API 호출이 되지 않게 <b>자동 갱신을 걸지 않는다</b>(PLAN D11).
 *
 * <p>⚠️ {@code IllegalArgumentException} 은 {@code GlobalExceptionHandler} 가 400 으로 매핑한다 —
 * 여기서 다시 잡지 말 것.
 */
@RestController
@RequestMapping("/api/admin/settlement")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SettlementController {

    /** 엑셀 MIME — {@code ShippingLabelController} 와 같은 값을 쓴다(다운로드 관례 통일). */
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SettlementSyncService settlementSyncService;
    private final SettlementPayoutSyncService settlementPayoutSyncService;
    private final SettlementReconciliationService settlementReconciliationService;
    private final CommissionFeedbackService commissionFeedbackService;

    /**
     * 수동 갱신 — delta 창만 다시 읽는다. 우선순위: accountId &gt; sellerId &gt; 전체.
     *
     * <p>계정별 최소 간격(기본 10분) 안이면 마켓을 호출하지 않고 {@code skipped=true} 로 돌아온다.
     */
    @PostMapping("/sync")
    public ResponseEntity<ResponseDTO<SettlementSyncResponse>> sync(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.sync(sellerId, accountId)));
    }

    /**
     * 기간 지정 백필(계정 1건). 31일을 넘는 구간은 어댑터가 잘라서 여러 번 호출한다.
     * {@code from > to} 이거나 계정이 없으면 400.
     */
    @PostMapping("/sync/period")
    public ResponseEntity<ResponseDTO<SettlementSyncResponse>> syncPeriod(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.syncPeriod(accountId, from, to)));
    }

    /** 대상 채널 목록 + 마지막 갱신 시각. 자격증명은 포함하지 않는다. */
    @GetMapping("/sync/targets")
    public ResponseEntity<ResponseDTO<List<SettlementSyncTargetResponse>>> syncTargets(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.targets(sellerId)));
    }

    /**
     * 지급내역 수동 갱신 — 지급 묶음 생성 + 라인 귀속 + 대사 (FEATURE_2609_30 / 02).
     *
     * <p>{@code month} 를 비우면 계정별로 자동 결정한다(최초 실행이면 백필 개월수, 아니면 당월+직전월).
     * 당월 이후를 요청하면 400 이다 — 플랫폼이 거절하는 요청을 우리가 먼저 막는다.
     */
    @PostMapping("/payout/sync")
    public ResponseEntity<ResponseDTO<SettlementPayoutSyncResponse>> syncPayouts(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(ResponseDTO.success(
                settlementPayoutSyncService.syncPayouts(accountId, month)));
    }

    /** 지급 묶음 목록. 🔴 {@code lineCount == 0} 인 묶음도 그대로 내려간다 — 정상 상태다(PLAN D5-4). */
    @GetMapping("/payouts")
    public ResponseEntity<ResponseDTO<List<PayoutSummary>>> payouts(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(
                settlementReconciliationService.payouts(sellerId, accountId, from, to)));
    }

    /**
     * 지급 묶음 목록 — <b>매출인식월</b> 축 (FEATURE_2609_34). 매출 화면이 채널 행을 펼칠 때 부른다.
     *
     * <p>🔴 위 {@code /payouts} 와 축이 다르다: 저쪽은 <b>지급일</b> 구간, 이쪽은 판매일 기간이 걸치는
     * <b>인식월</b> 구간이다. 매출 화면의 기간은 판매일이라 지급일로 자르면 "8월에 판 것을 9월에 받는" 건이
     * 통째로 빠진다. 한 엔드포인트에 플래그로 합치지 말 것 — 두 축이 섞이면 화면이 무엇을 본 건지 잃는다.
     *
     * <p>🔴 경로가 {@code /payouts/{id}} 보다 <b>먼저 매칭</b>돼야 한다. Spring 의 PathPattern 은 리터럴
     * 세그먼트를 변수보다 우선하므로 이대로 안전하다 — {@code {id}} 를 {@code String} 으로 바꾸는 순간
     * 깨지므로 그렇게 바꾸지 말 것.
     *
     * <p>{@code from > to} 는 400({@code IllegalArgumentException} → GlobalExceptionHandler).
     */
    @GetMapping("/payouts/by-recognition")
    public ResponseEntity<ResponseDTO<List<PayoutSummary>>> payoutsByRecognitionMonth(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(settlementReconciliationService
                .payoutsByRecognitionMonth(sellerId, accountId, from, to)));
    }

    /**
     * 판매월 기준 정산 — "그 달 판매가 언제 얼마로 정산됐나" (FEATURE_2609_34).
     *
     * <p>🔴 위 두 목록과 축이 다르다: {@code /payouts} 는 지급일, {@code /payouts/by-recognition} 은 판매월로
     * <b>정산 건</b>을 찾고, 이쪽은 <b>판매</b>에서 정산 시점을 올려다본다. 한 달 판매가 여러 번에 나눠
     * 정산되는 경우는 이쪽에서만 보인다 — 셋을 한 엔드포인트로 합치지 말 것.
     */
    @GetMapping("/by-sale-month")
    public ResponseEntity<ResponseDTO<List<SaleMonthSettlement>>> bySaleMonth(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(
                settlementReconciliationService.bySaleMonth(accountId, from, to)));
    }

    /** 묶음 1건 + 조정 행 + 검증식 요약 금액. 없는 id 는 400. */
    @GetMapping("/payouts/{id}")
    public ResponseEntity<ResponseDTO<SettlementPayoutDetailResponse>> payout(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(settlementReconciliationService.payout(id)));
    }

    /** 묶음의 라인 목록 — 원인 라벨 필터 · 미분류만 보기. */
    @GetMapping("/payouts/{id}/lines")
    public ResponseEntity<ResponseDTO<List<ReconLineView>>> payoutLines(
            @PathVariable Long id,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) Boolean unmatched) {
        return ResponseEntity.ok(ResponseDTO.success(
                settlementReconciliationService.lines(id, label, unmatched)));
    }

    /** 차이 리포트 2단 (PLAN D12). */
    @GetMapping("/payouts/{id}/report")
    public ResponseEntity<ResponseDTO<ReconReportResponse>> payoutReport(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(settlementReconciliationService.report(id)));
    }

    /** 리포트 ①의 라인 목록을 xlsx 로 내려받는다. */
    @GetMapping("/payouts/{id}/export")
    public ResponseEntity<byte[]> payoutExport(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"settlement-payout-" + id + ".xlsx\"")
                .body(settlementReconciliationService.export(id));
    }

    /**
     * 실측 수수료율 제안 목록 (FEATURE_2609_30 / 06 · PLAN D16).
     *
     * <p>기간을 비우면 최근 3개월이다. 표본이 {@code minSamples}(기본 5) 미만이거나 차이가 0.1%p 미만인
     * 카테고리는 목록에 없다 — 제안이 아니라 소음이라서다. 시드 누락({@code commissionRate = null})은
     * 성격이 달라 {@code seedingGaps} 로 따로 내려간다.
     */
    @GetMapping("/commission-suggestions")
    public ResponseEntity<ResponseDTO<CommissionSuggestionResponse>> commissionSuggestions(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer minSamples) {
        return ResponseEntity.ok(ResponseDTO.success(
                commissionFeedbackService.suggestions(sellerId, from, to, minSamples)));
    }

    /**
     * 사용자가 고른 카테고리의 수수료율 확정 반영.
     *
     * <p>🔴 요청에 실린 항목만 바꾼다. 🔴 셀 판매가는 건드리지 않는다 — 응답의 {@code notice} 가
     * [원가/가격 반영]으로 넘긴다(PLAN 2609_28 D4).
     *
     * <p>범위를 벗어난 비율은 400, 화면을 열어둔 사이 실측이 0.5%p 넘게 움직였으면 409 다.
     */
    @PostMapping("/commission-suggestions/apply")
    public ResponseEntity<ResponseDTO<CommissionApplyResponse>> applyCommissionSuggestions(
            @Valid @RequestBody CommissionApplyRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(commissionFeedbackService.apply(request)));
    }
}
