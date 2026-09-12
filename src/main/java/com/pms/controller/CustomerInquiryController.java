package com.pms.controller;

import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.InquirySyncResponse;
import com.pms.dto.response.InquiryTypeCatalogResponse;
import com.pms.service.inquiry.InquiryQueryService;
import com.pms.service.inquiry.InquirySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 고객문의 조회 + 문의만 다시 가져오기 API (FEATURE_2609_23). 인증된 사용자 대상 — 권한은 SecurityConfig 의
 * {@code anyRequest().authenticated()} 적용({@link OrderClaimController} 와 동일). 역할 제한이 없으므로
 * {@code @PreAuthorize} 를 붙이지 않는다.
 *
 * <p>⚠️ 답변 <b>전송</b>은 여기 붙이지 않는다 — 마켓에 되돌릴 수 없는 쓰기라 경로부터 분리한다
 * ({@code /api/admin/inquiries/…}, 04 범위).
 */
@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class CustomerInquiryController {

    private final InquiryQueryService inquiryQueryService;
    private final InquirySyncService inquirySyncService;

    /**
     * 문의 목록 조회 (최신 문의일순). 필터는 전부 선택이며 {@code accountId} 와 {@code sellerId} 는
     * 함께 올 수 있다(채널이 판매자에 속하므로 AND).
     * from·to(yyyy-MM-dd)를 함께 주면 그 기간(문의일 기준, 양끝 포함)을, 없으면 최근 14일을 반환한다.
     * 둘 중 하나만 주거나 from &gt; to 면 400.
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<CustomerInquiryResponse>>> list(
            @RequestParam(required = false) InquiryType type,
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ResponseDTO.success(
                inquiryQueryService.getInquiries(type, status, accountId, sellerId, from, to, keyword)));
    }

    /**
     * 플랫폼별 지원 문의 유형 (D4) — 프론트 유형 탭의 <b>유일한</b> 원천이다.
     * {@code /{id}} 보다 먼저 선언해 리터럴 경로가 가려지지 않게 둔다.
     */
    @GetMapping("/types")
    public ResponseEntity<ResponseDTO<List<InquiryTypeCatalogResponse>>> types() {
        return ResponseEntity.ok(ResponseDTO.success(inquiryQueryService.getTypes()));
    }

    /** 문의 단건 조회 — 답변 스레드 + 관련 주문 + 관련 상품 포함. 없는 id 면 404. */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseDTO<CustomerInquiryResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(inquiryQueryService.getInquiry(id)));
    }

    /**
     * 채널 1개의 문의를 마켓에서 다시 가져온다. 문의만 보려고 주문 동기화 전체를 돌리지 않기 위한
     * 입구이며, 적재 규칙은 주문 동기화 경로와 같은 한 벌이다(2609_23 D12 는 그대로 유지).
     *
     * <p>여러 채널은 화면이 하나씩 부른다 — 진행 상황과 채널별 실패를 그리기 위해서다.
     * 없는 계정이면 404, 문의 조회를 지원하지 않는 플랫폼이면 400.
     *
     * <p>⚠️ 마켓에 쓰는 동작이 아니라 읽어와 저장하는 동작이라 조회 경로에 둔다
     * ({@code POST /api/orders/sync} 와 같은 자리). 답변 전송만 {@code /api/admin/…} 으로 분리돼 있다.
     */
    @PostMapping("/sync")
    public ResponseEntity<ResponseDTO<InquirySyncResponse>> sync(@RequestParam Long accountId) {
        return ResponseEntity.ok(ResponseDTO.success(inquirySyncService.sync(accountId)));
    }
}
