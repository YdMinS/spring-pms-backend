package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.AlertSummaryResponse;
import com.pms.service.alert.AlertSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 배지 카운트 (FEATURE_2609_49 / D9).
 *
 * <p>🔴 <b>창구를 하나로 유지한다.</b> 추후 알림 기능·Dashboard 표기가 같은 값을 쓰기로 되어 있으므로,
 * 화면마다 자기 목록을 세지 말고 이 응답을 쓴다. 항목이 늘면 {@link AlertSummaryResponse} 에 필드를
 * 추가한다 — 엔드포인트를 늘리지 말 것.
 *
 * <p>권한은 {@code anyRequest().authenticated()}({@link CustomerInquiryController} 와 동일) — 역할
 * 제한이 없으므로 {@code @PreAuthorize} 를 붙이지 않는다.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertSummaryController {

    private final AlertSummaryService alertSummaryService;

    /** 처리 대기 건수. 마켓을 치지 않고 로컬 DB 만 센다 — 화면이 짧은 주기로 불러도 안전하다. */
    @GetMapping("/summary")
    public ResponseEntity<ResponseDTO<AlertSummaryResponse>> summary() {
        return ResponseEntity.ok(ResponseDTO.success(alertSummaryService.summary()));
    }
}
