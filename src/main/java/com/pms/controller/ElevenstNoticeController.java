package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstNoticePreviewRequest;
import com.pms.service.elevenst.ElevenstNoticeConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 11st view of the master notice form (FEATURE_2610_13 / D97 · D99 · D100): converts the values the screen
 * holds with the same {@link ElevenstNoticeConverter} the 11st payload uses, so the screen shows exactly what
 * is sent. Pure computation — no save, no 11st call.
 */
@RestController
@RequestMapping("/api/admin/elevenst")
@Tag(name = "11st Notice", description = "11st product notification preview (ADMIN only)")
public class ElevenstNoticeController {

    @PostMapping("/notice-preview")
    @Operation(summary = "Convert master notice values to the 11st notification (type, items, 11st-only keys)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstNoticeConverter.Notification>> preview(
            @RequestBody ElevenstNoticePreviewRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                ElevenstNoticeConverter.convert(request.getGroup(), request.getNotices())));
    }
}
