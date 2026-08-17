package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.service.DetailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Detail-page template library reads (FEATURE_2608_06 / Step 2-1). Block editing (create/update) is a
 * later editor.
 *
 * <p>ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Reads are tenant-scoped in the service.</p>
 */
@RestController
@RequestMapping("/api/admin/detail-templates")
@RequiredArgsConstructor
@Tag(name = "Detail Template", description = "Detail-page template library (ADMIN only)")
public class DetailTemplateController {

    private final DetailTemplateService detailTemplateService;

    @GetMapping
    @Operation(summary = "List detail templates")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<DetailTemplateResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.list()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get detail template by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailTemplateResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.get(id)));
    }
}
