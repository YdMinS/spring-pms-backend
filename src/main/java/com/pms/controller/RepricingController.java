package com.pms.controller;

import com.pms.domain.Platform;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PriceOverrideRequest;
import com.pms.dto.request.RecalculateRequest;
import com.pms.dto.request.RepricePushRequest;
import com.pms.dto.response.PriceOverrideResult;
import com.pms.dto.response.RecalculateResult;
import com.pms.dto.response.RepricePushResult;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.service.price.RepricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마진 경보 API — ADMIN 전용 (FEATURE_2609_39 / PLAN D2·D7).
 *
 * <p>🔴 <b>조회는 아무것도 바꾸지 않는다.</b> 비용(택배비·박스비·수수료율·매입가)이 움직여도 판매가는 저절로
 * 재계산되지 않는다 — 그 구간을 드러내는 것이 이 화면의 목적이고, 실행은 사람이 별도 액션으로 한다(D1).</p>
 *
 * <p>🔴 실행은 <b>두 엔드포인트로 끝까지 나뉜다</b>(D7): {@code recalculate} 는 로컬 판매가만 다시 계산하고,
 * {@code push} 만 실제 마켓 가격을 바꾼다. 한 버튼으로 합치면 매입 단가 오타 하나가 한 번의 클릭으로
 * 실판매가가 되고, 쿠팡 가격변경은 승인 없이 즉시 반영이라 되돌릴 수 없다.</p>
 *
 * @see RepricingService
 */
@RestController
@RequestMapping("/api/admin/repricing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RepricingController {

    private final RepricingService repricingService;

    /**
     * 대응 필요 목록 + 판매자 × 채널 집계. 저장·전송 없음.
     *
     * @param sellerId 판매자 필터(생략 = 전 판매자)
     * @param platform 채널 필터(생략 = 전 채널)
     * @param scope    {@code BELOW}(기본, 대응 필요만) 또는 {@code ALL}(대상 전부)
     */
    @GetMapping("/candidates")
    public ResponseEntity<ResponseDTO<RepricingCandidatesResponse>> candidates(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) RepricingService.Scope scope) {
        // Platform.from(null) 은 400 이므로 널 체크는 경계인 여기서 한다(2609_26 / D16 규칙).
        Platform resolved = platform == null ? null : Platform.from(platform);
        return ResponseEntity.ok(ResponseDTO.success(
                repricingService.candidates(sellerId, resolved, scope)));
    }

    /**
     * ① 재계산 — 선택한 셀의 판매가를 다시 계산한다. <b>마켓 호출 0회</b>.
     *
     * <p>🔴 그 셀의 AUTO 옵션이 <b>전부</b> 다시 계산된다(D21). 상한(200)은 요청 DTO 가 소유한다.</p>
     */
    @PostMapping("/recalculate")
    public ResponseEntity<ResponseDTO<RecalculateResult>> recalculate(
            @Valid @RequestBody RecalculateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                repricingService.recalculate(request.listingIds())));
    }

    /**
     * ② 마켓 반영 — 재계산된 판매가를 마켓으로 보낸다. 🔴 <b>실제 판매 가격이 바뀐다.</b>
     *
     * <p>부분 실패는 정상 경로다(200 + {@code failed}). 429 쿨다운이면 중단하고 지금까지의 결과와
     * 재시도 가능 시각을 함께 돌려준다.</p>
     */
    @PostMapping("/push")
    public ResponseEntity<ResponseDTO<RepricePushResult>> push(
            @Valid @RequestBody RepricePushRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                repricingService.push(request.optionIds())));
    }

    /**
     * 판매가 직접 입력 — 사람이 친 값을 로컬 판매가에 적는다. <b>마켓 호출 0회</b>.
     *
     * <p>🔴 {@code price_source} 는 그대로다(2609_42 / D2) — 다음 재계산이 공식값으로 덮는 것이 정상이다.
     * 입력값을 마켓에 반영하려면 {@code push} 를 따로 부른다(D1).</p>
     *
     * <p>부분 건너뜀은 정상 경로다(200 + {@code skipped}): 직접 지정가(D4)·판매중이 아닌 셀은 저장하지 않는다.</p>
     */
    @PostMapping("/override")
    public ResponseEntity<ResponseDTO<PriceOverrideResult>> override(
            @Valid @RequestBody PriceOverrideRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                repricingService.override(request.items())));
    }
}
