package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.LotteonNoticePreviewRequest;
import com.pms.service.lotteon.LotteonNoticeConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The LotteON view of the master notice form (FEATURE_2610_24 / D56): converts the values the screen holds with the
 * same {@link LotteonNoticeConverter} the LotteON payload uses, so the screen shows exactly what is sent. Pure
 * computation — no save, no LotteON call. Same shape as {@code EsmNoticeController}.
 */
@RestController
@RequestMapping("/api/admin/lotteon")
@Tag(name = "LotteON Notice", description = "LotteON product notice preview (ADMIN only)")
public class LotteonNoticeController {

    @PostMapping("/notice-preview")
    @Operation(summary = "Convert master notice values to the LotteON product notice (item group, items, LotteON-only keys)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<LotteonNoticeConverter.Notification>> preview(
            @RequestBody LotteonNoticePreviewRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                LotteonNoticeConverter.convert(request.getGroup(), request.getNotices())));
    }
}
