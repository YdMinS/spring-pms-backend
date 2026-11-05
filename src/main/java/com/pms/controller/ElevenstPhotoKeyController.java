package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstPhotoKeyRequest;
import com.pms.dto.response.ElevenstPhotoKeyResponse;
import com.pms.service.elevenst.ElevenstPhotoKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's 11st photo lookup key (FEATURE_2611_11 / D39) — web [설정] > 「11번가 사진 조회 키」.
 *
 * <p>ADMIN only through the {@code /api/admin/**} rules of {@code SecurityConfig} (GET and PUT alike). 🔴 The key is
 * write-only: the answer says whether one is stored, never the key.</p>
 */
@RestController
@RequestMapping("/api/admin/elevenst-photo-key")
@RequiredArgsConstructor
@Tag(name = "11st Photo Key", description = "The company's 11st public OpenAPI key for reading product photos (ADMIN)")
public class ElevenstPhotoKeyController {

    private final ElevenstPhotoKeyService elevenstPhotoKeyService;

    @GetMapping
    @Operation(summary = "Whether the company has an 11st photo lookup key (the key is never returned)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "configured")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ElevenstPhotoKeyResponse>> get() {
        return ResponseEntity.ok(ResponseDTO.success(elevenstPhotoKeyService.get()));
    }

    @PutMapping
    @Operation(summary = "Store the company's 11st photo lookup key (blank clears it)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "configured after the save")
    @ApiResponse(responseCode = "400", description = "Key longer than 100 characters")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ElevenstPhotoKeyResponse>> update(
            @Valid @RequestBody ElevenstPhotoKeyRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(elevenstPhotoKeyService.update(request)));
    }
}
