package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.service.external.LoggingAdminService;
import com.pms.service.external.LoggingAdminService.TargetStatus;
import com.pms.service.external.LoggingAdminService.TargetSummary;
import com.pms.service.external.LoggingTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 외부연동 로그레벨 런타임 토글 컨트롤러 (ADMIN 전용).
 *
 * <p>PATCH 사용: SecurityConfig 의 {@code /api/admin/**} 글로벌 룰이 PUT 을 포함하지 않으므로
 * 토글은 PATCH 로 처리해 별도 보안설정 없이 ADMIN 권한이 적용되게 한다.
 * 잘못된 target 은 400, 잘못된 level 은 GlobalExceptionHandler 의 IllegalArgumentException→400 처리.
 */
@RestController
@RequestMapping("/api/admin/logging")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Logging Configuration",
        description = "외부연동(쿠팡 등) 로그레벨 런타임 토글 API (ADMIN 전용). DEBUG는 30분 후 자동 INFO 복귀.")
public class LoggingAdminController {

    private final LoggingAdminService loggingAdminService;

    /** PATCH 바디: level 은 DEBUG|INFO 만 허용. */
    public record LevelRequest(@Pattern(regexp = "DEBUG|INFO") String level) {}

    /** 토글 가능한 연동 대상 목록(웹/모바일 select 소스). */
    @GetMapping("/targets")
    @Operation(summary = "List logging targets",
            description = "토글 가능한 외부연동 대상 목록(key/label). 웹·모바일 select 소스 (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Targets retrieved successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = TargetSummary.class))))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public List<TargetSummary> listTargets() {
        return loggingAdminService.listTargets();
    }

    /** 대상의 현재 유효 로그레벨 조회. */
    @GetMapping("/{target}")
    @Operation(summary = "Get logging level",
            description = "대상의 현재 유효 로그레벨 조회. DEBUG면 autoRevertAt(자동복귀 예정시각) 포함 (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Current level retrieved successfully",
            content = @Content(schema = @Schema(implementation = TargetStatus.class)))
    @ApiResponse(responseCode = "400", description = "Unknown logging target",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public TargetStatus get(
            @PathVariable
            @Parameter(description = "Logging target enum key (예: COUPANG)")
            String target) {
        return loggingAdminService.get(parse(target));
    }

    /** 대상의 로그레벨 설정(on/off). DEBUG 설정 시 30분 자동복귀 예약. */
    @PatchMapping("/{target}")
    @Operation(summary = "Set logging level",
            description = "대상의 로그레벨을 DEBUG|INFO 로 설정. DEBUG 설정 시 autoRevertAt = now+30분 (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Level updated successfully",
            content = @Content(schema = @Schema(implementation = TargetStatus.class)))
    @ApiResponse(responseCode = "400", description = "Unknown logging target, or level not in {DEBUG, INFO}",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public TargetStatus set(
            @PathVariable
            @Parameter(description = "Logging target enum key (예: COUPANG)")
            String target,
            @Valid @RequestBody LevelRequest request) {
        return loggingAdminService.set(parse(target), request.level());
    }

    /** 화이트리스트 파싱: 없으면 IllegalArgumentException → 400. */
    private LoggingTarget parse(String target) {
        return LoggingTarget.from(target)
                .orElseThrow(() -> new IllegalArgumentException("Unknown logging target: " + target));
    }
}
