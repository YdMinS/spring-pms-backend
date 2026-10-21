package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.EsmNoticePreviewRequest;
import com.pms.service.esm.EsmNoticeConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ESM view of the master notice form (FEATURE_2610_26 / D56): converts the values the screen holds with the same
 * {@link EsmNoticeConverter} the ESM payload uses, so the screen shows exactly what is sent. Pure computation — no
 * save, no ESM call. Same shape as {@code ElevenstNoticeController}.
 */
@RestController
@RequestMapping("/api/admin/esm")
@Tag(name = "ESM Notice", description = "ESM official notice preview (ADMIN only)")
public class EsmNoticeController {

    @PostMapping("/notice-preview")
    @Operation(summary = "Convert master notice values to the ESM official notice (group, items, ESM-only keys)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<EsmNoticeConverter.Notification>> preview(
            @RequestBody EsmNoticePreviewRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                EsmNoticeConverter.convert(request.getGroup(), request.getNotices())));
    }
}
