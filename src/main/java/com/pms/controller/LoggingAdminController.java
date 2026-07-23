package com.pms.controller;

import com.pms.service.external.LoggingAdminService;
import com.pms.service.external.LoggingAdminService.TargetStatus;
import com.pms.service.external.LoggingAdminService.TargetSummary;
import com.pms.service.external.LoggingTarget;
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
public class LoggingAdminController {

    private final LoggingAdminService loggingAdminService;

    /** PATCH 바디: level 은 DEBUG|INFO 만 허용. */
    public record LevelRequest(@Pattern(regexp = "DEBUG|INFO") String level) {}

    @GetMapping("/targets")
    public List<TargetSummary> listTargets() {
        return loggingAdminService.listTargets();
    }

    @GetMapping("/{target}")
    public TargetStatus get(@PathVariable String target) {
        return loggingAdminService.get(parse(target));
    }

    @PatchMapping("/{target}")
    public TargetStatus set(@PathVariable String target, @Valid @RequestBody LevelRequest request) {
        return loggingAdminService.set(parse(target), request.level());
    }

    /** 화이트리스트 파싱: 없으면 IllegalArgumentException → 400. */
    private LoggingTarget parse(String target) {
        return LoggingTarget.from(target)
                .orElseThrow(() -> new IllegalArgumentException("Unknown logging target: " + target));
    }
}
