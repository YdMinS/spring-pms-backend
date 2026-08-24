package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.service.coupang.CoupangCancelDiagnosticService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ⚠️ TEMPORARY 진단 전용 — 취소 미반영 원인 규명 후 제거.
 *
 * GET /api/admin/diag/coupang-cancel?orderId=... (ADMIN, SecurityConfig 글로벌 /api/admin/**).
 * 우리 order_item 행 + 쿠팡 returnRequests 매칭/구조 + 단건 주문서 현재 상태를 한 번에 반환.
 */
@RestController
@RequestMapping("/api/admin/diag")
@RequiredArgsConstructor
public class CoupangCancelDiagnosticController {

    private final CoupangCancelDiagnosticService diagnosticService;

    @GetMapping("/coupang-cancel")
    public ResponseDTO<Map<String, Object>> diagnose(@RequestParam String orderId) {
        return ResponseDTO.success(diagnosticService.diagnose(orderId));
    }
}
