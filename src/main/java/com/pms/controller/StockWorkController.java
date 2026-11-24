package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OutboundWorkRequest;
import com.pms.service.stock.OutboundWorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「출고 작업」 · 「반품 재입고」 · 「교환 발송」 (FEATURE_2611_34 D35). ADMIN only, like every {@code /api/admin/**}
 * path.
 */
@RestController
@RequestMapping("/api/admin/stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockWorkController {

    private final OutboundWorkService outboundWorkService;

    /** 「출고 작업」 [폐기 저장] · [매입처 반품 저장] (D35 ⑤). */
    @PostMapping("/outbound-work")
    public ResponseEntity<ResponseDTO<Void>> outboundWork(@Valid @RequestBody OutboundWorkRequest request) {
        outboundWorkService.save(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
