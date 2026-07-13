package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PlatformCarrierCodeRequest;
import com.pms.dto.response.PlatformCarrierCodeResponse;
import com.pms.service.PlatformCarrierCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 플랫폼별 택배사 코드 관리 API (carrier 하위 중첩 리소스).
 *
 * 보안: SecurityConfig 변경 불필요 — 기존 matcher 가 커버한다.
 * (GET /api/admin/carriers/** → authenticated, /api/admin/carriers/** → hasRole("ADMIN"))
 */
@RestController
@RequestMapping("/api/admin/carriers/{carrierId}/platform-codes")
@RequiredArgsConstructor
@Tag(name = "PlatformCarrierCode", description = "플랫폼별 택배사 코드 관리 API")
public class PlatformCarrierCodeController {

    private final PlatformCarrierCodeService platformCarrierCodeService;

    @GetMapping
    @Operation(summary = "List platform carrier codes",
            description = "Get all platform codes of a carrier (authenticated users)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Codes retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<PlatformCarrierCodeResponse>>> getCodes(
            @PathVariable
            @Parameter(description = "Carrier ID")
            Long carrierId) {
        List<PlatformCarrierCodeResponse> response = platformCarrierCodeService.getCodes(carrierId);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PostMapping
    @Operation(summary = "Create platform carrier code",
            description = "Create a platform code for a carrier (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Code created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "409", description = "Duplicate platform for this carrier",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PlatformCarrierCodeResponse>> createCode(
            @PathVariable
            @Parameter(description = "Carrier ID")
            Long carrierId,
            @Valid @RequestBody PlatformCarrierCodeRequest request) {
        PlatformCarrierCodeResponse response = platformCarrierCodeService.createCode(carrierId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    @PatchMapping("/{codeId}")
    @Operation(summary = "Update platform carrier code",
            description = "Full-replace platform/deliveryCompanyCode (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Code updated successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier or code not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "409", description = "Duplicate platform for this carrier",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PlatformCarrierCodeResponse>> updateCode(
            @PathVariable
            @Parameter(description = "Carrier ID")
            Long carrierId,
            @PathVariable
            @Parameter(description = "Platform carrier code ID")
            Long codeId,
            @Valid @RequestBody PlatformCarrierCodeRequest request) {
        PlatformCarrierCodeResponse response =
                platformCarrierCodeService.updateCode(carrierId, codeId, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{codeId}")
    @Operation(summary = "Delete platform carrier code",
            description = "Delete a platform code permanently (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Code deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier or code not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> deleteCode(
            @PathVariable
            @Parameter(description = "Carrier ID")
            Long carrierId,
            @PathVariable
            @Parameter(description = "Platform carrier code ID")
            Long codeId) {
        platformCarrierCodeService.deleteCode(carrierId, codeId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
