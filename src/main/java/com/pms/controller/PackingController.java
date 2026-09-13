package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.BoxCandidateRequest;
import com.pms.dto.request.ParcelCompleteRequest;
import com.pms.dto.response.BarcodeLookupResponse;
import com.pms.dto.response.PackingScanResponse;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.dto.response.ParcelCompleteResponse;
import com.pms.dto.response.PendingParcelView;
import com.pms.service.packing.BoxCandidate;
import com.pms.service.packing.PackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 포장 콘솔 API — ADMIN 전용 (FEATURE_2609_40 / PLAN D9 ~ D19 · D27 ~ D32).
 *
 * <p>🔴 {@code hasRole('ADMIN')} 은 <b>현장 작업자 계정도 ADMIN 이어야 한다</b>는 뜻이다. 권한만 따로
 * 정하게 되더라도 로직·응답은 그대로다.
 *
 * <p>🔴 스캔({@link #scan})은 <b>아무것도 만들지 않는다</b>(D19). 재고가 움직이는 것은
 * {@link #complete} 한 곳뿐이며 그것이 사람의 확인이다.
 *
 * @see PackingService
 */
@RestController
@RequestMapping("/api/admin/packing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PackingController {

    private final PackingService packingService;

    /** 송장번호 → 박스 + 담을 것 + 상자 후보. 못 찾으면 404, 이미 닫힌 박스는 200 + 상태. */
    @GetMapping("/scan")
    public ResponseEntity<ResponseDTO<PackingScanResponse>> scan(@RequestParam String invoiceNumber) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.scan(invoiceNumber)));
    }

    /**
     * 작업 대상 박스 목록 — {@code PENDING} 이면서 잔량 &gt; 0 인 것만(D16 · D31).
     *
     * @param sellerId 판매자 필터 (생략 = 전 판매자)
     */
    @GetMapping("/pending")
    public ResponseEntity<ResponseDTO<List<PendingParcelView>>> pending(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.pending(sellerId)));
    }

    /**
     * 못 맞춘 바코드 조회 — 「등록되지 않은 바코드」와 「이 주문에 없는 물품」을 구분해 준다(D11 · D12).
     *
     * <p>⚠️ {@code parcelId} 는 필수지만 {@code required = false} 로 받는다: 빠졌을 때 프레임워크의
     * 기본 메시지 대신 서비스가 사람 말로 400 을 돌려준다.
     */
    @GetMapping("/barcode")
    public ResponseEntity<ResponseDTO<BarcodeLookupResponse>> barcode(
            @RequestParam String value,
            @RequestParam(required = false) Long parcelId) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.barcode(value, parcelId)));
    }

    /** 담은 조합 → 상자 후보(최대 3, 사용 횟수 순). 기억이 없으면 빈 목록 + 200 이다(D23). */
    @PostMapping("/box-candidates")
    public ResponseEntity<ResponseDTO<List<BoxCandidate>>> boxCandidates(
            @Valid @RequestBody BoxCandidateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.boxCandidates(request)));
    }

    /** [이 박스 완료] — 박스 내용 + 출고 + 절약 + 상자 기억. 멱등이다(D15). */
    @PostMapping("/parcels/{id}/complete")
    public ResponseEntity<ResponseDTO<ParcelCompleteResponse>> complete(
            @PathVariable Long id,
            @Valid @RequestBody ParcelCompleteRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.complete(id, request)));
    }

    /** 송장을 안 쓴 박스를 닫는다 — 출고·기억·절약 없음(D32). */
    @PostMapping("/parcels/{id}/unused")
    public ResponseEntity<ResponseDTO<ParcelCloseResponse>> unused(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(packingService.unused(id)));
    }
}
